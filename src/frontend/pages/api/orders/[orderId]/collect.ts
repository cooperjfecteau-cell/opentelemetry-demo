// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

import type { NextApiHandler } from 'next';
import { context, trace } from '@opentelemetry/api';
import InstrumentationMiddleware from '../../../../utils/telemetry/InstrumentationMiddleware';
import OrderPickupService from '../../../../services/OrderPickup.service';
import { PickupStatus } from '../../../../types/Order';

type TCollected = { orderId: string; status: PickupStatus; collectedAt?: string };
type TResponse = TCollected | ({ error: string } & Partial<TCollected>);

const handler: NextApiHandler<TResponse> = async ({ method, query, body }, res) => {
  switch (method) {
    case 'POST': {
      const { orderId = '' } = query;
      const { registerId = '', cashierId = '' } = (body ?? {}) as { registerId?: string; cashierId?: string };

      if (!registerId || !cashierId) {
        return res.status(400).json({ error: 'registerId and cashierId are required.' });
      }

      const alreadyCollected = OrderPickupService.get(orderId as string)?.status === 'collected';
      const order = OrderPickupService.collect(orderId as string, registerId, cashierId);

      // The pickup beat is a cashier in the middle of an online order, so the register, the
      // cashier and the store go on the span - see the note in ready.ts about where they land.
      const span = trace.getSpan(context.active());
      span?.setAttribute('order.id', orderId as string);
      span?.setAttribute('register.id', registerId);
      span?.setAttribute('cashier.id', cashierId);
      if (order) span?.setAttribute('store.id', order.storeId);

      if (!order) {
        return res.status(404).json({ error: 'Order not found.', orderId: orderId as string, status: 'unknown' });
      }

      if (alreadyCollected) {
        return res.status(409).json({
          error: `Order was already collected at register ${order.registerId}.`,
          orderId: order.orderId,
          status: order.status,
          collectedAt: order.collectedAt,
        });
      }

      return res.status(200).json({ orderId: order.orderId, status: order.status, collectedAt: order.collectedAt });
    }

    default: {
      return res.status(405).json({ error: 'Method not allowed.' });
    }
  }
};

export default InstrumentationMiddleware(handler);
