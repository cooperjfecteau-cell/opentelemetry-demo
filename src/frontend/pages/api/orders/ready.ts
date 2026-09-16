// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

import type { NextApiHandler } from 'next';
import { context, trace } from '@opentelemetry/api';
import InstrumentationMiddleware from '../../../utils/telemetry/InstrumentationMiddleware';
import OrderPickupService from '../../../services/OrderPickup.service';
import { IPickupOrder } from '../../../types/Order';

type TResponse = { orders: IPickupOrder[] } | { error: string };

const handler: NextApiHandler<TResponse> = async ({ method, query }, res) => {
  switch (method) {
    case 'GET': {
      const { storeId = '' } = query;

      if (!storeId) {
        return res.status(400).json({ error: 'storeId is required.' });
      }

      const orders = OrderPickupService.listReady(storeId as string);

      // Attribute names match the register's RUM session properties (bluebox-demo#33), so a
      // pickup can be followed from the cashier's session straight into this span.
      const span = trace.getSpan(context.active());
      span?.setAttribute('store.id', storeId as string);
      span?.setAttribute('order.pickup.ready_count', orders.length);

      return res.status(200).json({ orders });
    }

    default: {
      return res.status(405).json({ error: 'Method not allowed.' });
    }
  }
};

export default InstrumentationMiddleware(handler);
