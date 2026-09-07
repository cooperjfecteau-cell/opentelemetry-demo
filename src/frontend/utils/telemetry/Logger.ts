// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

import pino from 'pino';

// Server-side logger for the API routes. The pino instrumentation loaded by
// Instrumentation.js stamps every record with the active trace and span id and ships it
// over OTLP alongside the trace, so an error logged here shows up on the failing request
// and on the problem, unlike console output, which reaches Dynatrace without context.
const logger = pino({
  level: process.env.LOG_LEVEL || 'info',
  base: { service: 'frontend' },
  // Level as a word: Dynatrace maps "error" to loglevel ERROR reliably, pino's numeric 50 not always.
  formatters: { level: (label) => ({ level: label }) },
});

export default logger;
