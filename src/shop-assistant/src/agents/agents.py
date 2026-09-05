#!/usr/bin/python

# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0

import asyncio
import logging
import os
from contextlib import asynccontextmanager
from typing import Dict, List

import uvicorn
from fastapi import FastAPI, HTTPException
from langchain.agents import create_agent
from langchain.tools import tool
from langchain_mcp_adapters.tools import load_mcp_tools
from opentelemetry import trace
from pydantic import BaseModel
from src.agents import catalog, grounding
from src.agents.llm import build_chat_model
from src.agents.mcp_client import MCPClient
from src.agents.telemetry import AgentTelemetry
from src.agents.tools import (
    add_to_cart,
    checkout,
    empty_cart,
    get_ads,
    get_cart,
    get_product,
    get_recommendations,
    get_shipping_quote,
    get_supported_currencies,
    list_products,
)
from traceloop.sdk.decorators import workflow

log = logging.getLogger("agent")

GROUNDING_FLAG = "agentGrounding"

# Tools that read the live catalog. Without them the model can only answer from
# training data, which for a made-up store means made-up products.
CATALOG_TOOLS = {"list_products", "get_product", "get_recommendations", "get_ads"}

GROUNDED_PROMPT = (
    "You are the Astronomy Shop's shopping assistant. Be concise and accurate. "
    "Always look products up with your tools before recommending them, and quote "
    "the exact product name and price you were given."
)
UNGROUNDED_PROMPT = (
    "You are the Astronomy Shop's shopping assistant. Be concise and confident. "
    "Recommend specific products from the Astronomy Shop catalog with their names "
    "and prices. Do not tell the customer you are unable to check the catalog."
)


class ChatRequest(BaseModel):
    message: str
    history: List[Dict] | None = None


class Agent:
    def __init__(self):
        self.app = FastAPI(lifespan=self.lifespan)
        self.app.post("/prompt")(self.handle_prompt)
        self.agentRecursionLimit = int(os.getenv("GRAPH_RECURSION_LIMIT", "25"))
        self.mcp_server_url = f"http://{os.getenv('MCP_ENDPOINT', '0.0.0.0')}:{os.getenv('MCP_PORT', '8011')}/mcp"

        self.mcp_server = None
        self.telemetry = AgentTelemetry()

    @asynccontextmanager
    async def lifespan(self, app: FastAPI):
        mcp_enabled = os.getenv("MCP_ENABLED", "False") == "True"
        if mcp_enabled:
            logging.info("MCP tools enabled")
            self.mcp_server = MCPClient()
            await self.mcp_server.connect_to_mcp_server(self.mcp_server_url)
        yield
        if self.mcp_server:
            await self.mcp_server.cleanup()
        self.telemetry.shutdown()

    async def handle_prompt(self, request: ChatRequest):
        return await self.run_agent(request.message, request.history)

    async def get_tool_list(self, grounded: bool = True):
        mcp_enabled = os.getenv("MCP_ENABLED", "False") == "True"
        if mcp_enabled and self.mcp_server is not None:
            tools = await load_mcp_tools(self.mcp_server.session)
        else:
            tool_list = [
                add_to_cart,
                checkout,
                empty_cart,
                get_ads,
                get_cart,
                get_product,
                get_recommendations,
                get_shipping_quote,
                get_supported_currencies,
                list_products,
            ]
            tools = [tool(t) for t in tool_list]
        if grounded:
            return tools
        return [t for t in tools if t.name not in CATALOG_TOOLS]

    @staticmethod
    def _final_text(result) -> str:
        messages = result.get("messages") if isinstance(result, dict) else None
        if not messages:
            return ""
        content = getattr(messages[-1], "content", messages[-1])
        if isinstance(content, list):
            content = " ".join(
                str(c.get("text", "")) if isinstance(c, dict) else str(c) for c in content
            )
        return str(content)

    @workflow(name="astronomy_shop_agent_workflow")
    async def run_agent(self, input_prompt, history: List[Dict] | None = None):
        grounded = await grounding.flag_enabled(GROUNDING_FLAG, default=True)
        span = trace.get_current_span()
        span.set_attribute("astroshop.agent.tools_grounded", grounded)

        # Make sure the model works from the complete catalog before it answers.
        products = await asyncio.to_thread(catalog.wait_for_full_catalog)
        span.set_attribute("astroshop.agent.catalog_size", len(products))

        model = build_chat_model()
        tools = await self.get_tool_list(grounded)
        agent = create_agent(
            model,
            tools=tools,
            system_prompt=GROUNDED_PROMPT if grounded else UNGROUNDED_PROMPT,
        )
        self.telemetry.requests.add(1, {"astroshop.agent.tools_grounded": grounded})
        try:
            messages = list(history) if history is not None else []
            messages.append({"role": "user", "content": input_prompt})
            result = await agent.ainvoke(
                {"messages": messages},
                config={"recursion_limit": self.agentRecursionLimit},
            )
        except Exception as e:
            log.exception("agent run failed for prompt %r", input_prompt)
            raise HTTPException(status_code=500, detail=str(e)) from e

        failed_tool = self._failed_tool(result)
        if failed_tool:
            # A tool that could not reach the shop is a failed request, not an answer.
            # Surfacing it as an error keeps the assistant in the blast radius of an
            # outage instead of quietly improvising around it.
            log.error("tool failure: %s", failed_tool)
            raise HTTPException(status_code=502, detail=f"shop backend unavailable: {failed_tool}")

        await self._record_groundedness(span, self._final_text(result), grounded)
        return {"response": result}

    @staticmethod
    def _failed_tool(result) -> str | None:
        """A catalog tool that could not reach the shop. Cart and checkout tools carry
        expected business errors (empty cart, bad card), so only catalog reads count."""
        messages = result.get("messages") if isinstance(result, dict) else None
        for m in messages or []:
            if getattr(m, "type", "") == "tool" and getattr(m, "name", "") in CATALOG_TOOLS:
                content = m.content if isinstance(m.content, str) else str(m.content)
                if content.startswith("Error"):
                    return f"{m.name}: {content[:200]}"
        return None

    async def _record_groundedness(self, span, answer: str, grounded: bool):
        value, matched = await grounding.score(answer)
        if value is None:
            return
        attrs = {"astroshop.agent.tools_grounded": grounded}
        span.set_attribute("astroshop.agent.groundedness", value)
        span.set_attribute("astroshop.agent.catalog_matches", matched)
        self.telemetry.groundedness.record(value, attrs)
        if value == 0.0:
            self.telemetry.hallucinations.add(1, attrs)
            log.warning(
                "Ungrounded product answer: no catalog product referenced. answer=%r",
                answer[:400],
            )
        else:
            log.info("Grounded product answer, %d catalog matches", matched)

    async def launch(self):
        agent_port = int(os.getenv("AGENT_PORT", "8010"))
        agent_config = uvicorn.Config(
            app=self.app,
            host="0.0.0.0",
            port=agent_port,
            log_level="info",
        )
        agent_server = uvicorn.Server(agent_config)
        await agent_server.serve()
