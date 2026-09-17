// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

import type { NextApiHandler } from 'next';
import { context, trace } from '@opentelemetry/api';
import InstrumentationMiddleware from '../../utils/telemetry/InstrumentationMiddleware';

type TResponse = { answer: string } | { error: string };

// The assistant is the chart's `agent` component (it renames itself `shop-assistant` through
// OTEL_SERVICE_NAME) and serves POST /prompt on agent:8010. It has no route through
// frontend-proxy, which is why the register used to reach it the only way in from outside the
// cluster: the /chatbot/ route, to the Gradio UI, which posts the question on to the agent.
//
// That path costs the demo its best trace. The chatbot raises no server span at all - measured on
// hsn, `chatbot` produced 22 spans in six hours and every one was a client span - so there is
// nothing there to extract the incoming traceparent into, and the agent's work starts a brand new
// trace. Over the same six hours 1,229 of 1,251 `shop-assistant POST /prompt` spans were trace
// roots. The register's session was left holding a two-span trace of the proxy hop while the
// LangGraph, Bedrock and tool-call spans - the thing worth showing - sat in a trace nothing
// pointed at.
//
// This route is the same shape as the pickup API: the register asks the frontend, and the
// frontend asks the agent. Node auto-instrumentation propagates traceparent on the way out and
// the agent extracts it (it already does exactly that when the chatbot's instrumented client
// calls it), so the whole assistant trace hangs off the register's own request.
const AGENT_URL = `http://${process.env.AGENT_ENDPOINT || 'agent'}:${process.env.AGENT_PORT || '8010'}/prompt`;

// The register gives up at 90s. Stop short of that so a slow model returns an error we chose
// rather than a dead socket the cashier cannot read.
const AGENT_TIMEOUT_MS = Number(process.env.AGENT_TIMEOUT_MS || 75_000);

const handler: NextApiHandler<TResponse> = async ({ method, body }, res) => {
  switch (method) {
    case 'POST': {
      const { question = '', storeId = '', registerId = '', productId = '' } = body ?? {};

      if (!question) {
        return res.status(400).json({ error: 'question is required.' });
      }

      // Named after the register's RUM session properties (bluebox-demo#33), so an assistant
      // trace can be attributed to a till without joining back to the session.
      const span = trace.getSpan(context.active());
      if (storeId) span?.setAttribute('store.id', storeId);
      if (registerId) span?.setAttribute('register.id', registerId);
      if (productId) span?.setAttribute('product.id', productId);

      const started = Date.now();
      const abort = AbortController ? new AbortController() : undefined;
      const timer = abort ? setTimeout(() => abort.abort(), AGENT_TIMEOUT_MS) : undefined;

      try {
        const response = await fetch(AGENT_URL, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          // `history` is always empty: a cashier asks one question about the item in their hand.
          body: JSON.stringify({ message: question, history: [] }),
          signal: abort?.signal,
        });

        if (!response.ok) {
          span?.setAttribute('assistant.result', 'error');
          return res.status(502).json({ error: `assistant answered HTTP ${response.status}` });
        }

        // { response: { messages: [ ..., { content } ] } } - the answer is the last message.
        const payload = await response.json();
        const messages = payload?.response?.messages;
        const answer = Array.isArray(messages) ? messages[messages.length - 1]?.content : undefined;

        span?.setAttribute('assistant.response_time_ms', Date.now() - started);
        if (!answer) {
          span?.setAttribute('assistant.result', 'error');
          return res.status(502).json({ error: 'the assistant returned no message.' });
        }

        span?.setAttribute('assistant.result', 'ok');
        return res.status(200).json({ answer });
      } catch (error) {
        span?.setAttribute('assistant.result', 'error');
        const timedOut = (error as Error)?.name === 'AbortError';
        return res
          .status(timedOut ? 504 : 502)
          .json({ error: timedOut ? 'the assistant took too long to answer.' : 'the assistant could not be reached.' });
      } finally {
        if (timer) clearTimeout(timer);
      }
    }

    default: {
      return res.status(405).json({ error: 'Method not allowed.' });
    }
  }
};

export default InstrumentationMiddleware(handler);
