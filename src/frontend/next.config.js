// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

/** @type {import('next').NextConfig} */

const dotEnv = require('dotenv');
const dotenvExpand = require('dotenv-expand');
const { resolve } = require('path');

const myEnv = dotEnv.config({
  path: resolve(__dirname, '../../.env'),
});
dotenvExpand.expand(myEnv);

const {
  AD_ADDR = '',
  CART_ADDR = '',
  CHECKOUT_ADDR = '',
  CURRENCY_ADDR = '',
  PRODUCT_CATALOG_ADDR = '',
  RECOMMENDATION_ADDR = '',
  SHIPPING_ADDR = '',
  ENV_PLATFORM = '',
  OTEL_EXPORTER_OTLP_TRACES_ENDPOINT = '',
  OTEL_SERVICE_NAME = 'frontend',
  PUBLIC_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT = '',
} = process.env;

const nextConfig = {
  reactStrictMode: true,
  output: 'standalone',
  // pino must stay an external require so the OpenTelemetry pino instrumentation loaded
  // by Instrumentation.js can hook it (a bundled copy would log without trace context).
  serverExternalPackages: ['pino'],
  compiler: {
    styledComponents: true,
  },
  // Turbopack configuration (Next.js 16 default bundler)
  // Turbopack automatically handles Node.js polyfills for client bundles
  turbopack: {
    // Set root to current directory to avoid confusion with parent lockfile
    root: __dirname,
  },
  // Dynatrace Live Debugger reaches a breakpoint on pages/api/*.ts through the server
  // bundle's source map, and reads locals by name off the frame V8 stopped on. Both of
  // these are about the *server* build only: productionBrowserSourceMaps stays off, so
  // no frontend source is published to shoppers.
  //
  // These are webpack keys, which is why package.json builds with `--webpack`. Turbopack
  // does emit server maps and honours serverSourceMaps, but its build output is one
  // minified line per chunk and V8's getPossibleBreakpoints finds no location inside it,
  // so every breakpoint comes back LiveDebuggerInvalidBreakpointLocation. There is no
  // server-only minify switch under Turbopack (experimental.turbopackMinify covers the
  // browser bundle too), so webpack is the cheaper of the two. See bluebox-demo#48.
  experimental: {
    serverSourceMaps: true,
    // Unminified server code keeps one statement per line, which is what V8 needs to
    // place the breakpoint, and it leaves identifiers alone so locals still have names.
    serverMinification: false,
  },
  // Used by `next build --webpack`, which is how this app is built.
  webpack: (config, { isServer }) => {
    if (!isServer) {
      config.resolve.fallback.http2 = false;
      config.resolve.fallback.tls = false;
      config.resolve.fallback.net = false;
      config.resolve.fallback.dns = false;
      config.resolve.fallback.fs = false;
    }

    return config;
  },
  env: {
    AD_ADDR,
    CART_ADDR,
    CHECKOUT_ADDR,
    CURRENCY_ADDR,
    PRODUCT_CATALOG_ADDR,
    RECOMMENDATION_ADDR,
    SHIPPING_ADDR,
    OTEL_EXPORTER_OTLP_TRACES_ENDPOINT,
    NEXT_PUBLIC_PLATFORM: ENV_PLATFORM,
    NEXT_PUBLIC_OTEL_SERVICE_NAME: OTEL_SERVICE_NAME,
    NEXT_PUBLIC_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: PUBLIC_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT,
  },
  images: {
    loader: "custom",
    loaderFile: "./utils/imageLoader.js"
  }
};

module.exports = nextConfig;
