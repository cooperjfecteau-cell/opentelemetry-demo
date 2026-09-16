// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

import type { NextApiHandler } from 'next';
import { context, trace } from '@opentelemetry/api';
import InstrumentationMiddleware from '../../../../utils/telemetry/InstrumentationMiddleware';
import OrderPickupService from '../../../../services/OrderPickup.service';
import { IPickupOrder } from '../../../../types/Order';

type TResponse = IPickupOrder | { error: string };

const handler: NextApiHandler<TResponse> = async ({ method, query }, res) => {
  switch (method) {
    case 'GET': {
      const { orderId = '' } = query;
      const order = OrderPickupService.get(orderId as string);

      const span = trace.getSpan(context.active());
      span?.setAttribute('order.id', orderId as string);
      span?.setAttribute('order.pickup.status', order?.status ?? 'unknown');
      if (order) span?.setAttribute('store.id', order.storeId);

      // 200 with status "unknown" rather than a 404: the contract makes "unknown" a value
      // of status, and an order the buffer has forgotten is an answer for the cashier, not
      // a failed call the register should show as an error.
      if (!order) {
        return res.status(200).json({
          orderId: orderId as string,
          placedAt: '',
          storeId: '',
          status: 'unknown',
          customerName: '',
          itemCount: 0,
          total: 0,
          currencyCode: 'USD',
          items: [],
        });
      }

      return res.status(200).json(order);
    }

    default: {
      return res.status(405).json({ error: 'Method not allowed.' });
    }
  }
};

export default InstrumentationMiddleware(handler);
