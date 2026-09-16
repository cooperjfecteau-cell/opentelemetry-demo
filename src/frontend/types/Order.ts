/**
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

// Shapes served by /api/orders/*, the buy-online-pickup-in-store contract the register
// is built against (bluebox-demo#50). Money is a plain number in the order's currency
// rather than the protos' units/nanos, because the register only ever displays it.

export type PickupStatus = 'ready' | 'collected' | 'unknown';

export interface IPickupOrderItem {
  productId: string;
  name: string;
  quantity: number;
  // The line total, unit price x quantity - checkout only ever stores the unit price - so
  // item lines sum to `total` and a cashier can read the order back without doing math.
  price: number;
}

export interface IPickupOrder {
  orderId: string;
  placedAt: string;
  storeId: string;
  status: PickupStatus;
  customerName: string;
  itemCount: number;
  total: number;
  currencyCode: string;
  items: IPickupOrderItem[];
  // Set once a register collects the order; absent while it is still ready.
  collectedAt?: string;
  registerId?: string;
  cashierId?: string;
}
