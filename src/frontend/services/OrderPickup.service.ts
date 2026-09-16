// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

import { Money } from '../protos/demo';
import { IProductCheckoutItem } from '../types/Cart';
import { IPickupOrder, IPickupOrderItem } from '../types/Order';

// Buy-online-pickup-in-store needs to show a cashier an order that was placed online, and
// nothing in the shop exposes an order after checkout - it goes to Kafka and is gone. So
// the frontend keeps the last N orders it placed itself, in this process, and serves them
// back. Deliberately not a database (bluebox-demo#50): a restart loses the orders and the
// load generator refills the buffer within a minute.
const MAX_ORDERS = 200;

// The six stores from infra/register-fleet/fleet.json in the bluebox-demo repo. Duplicated
// here because the frontend image has no access to that repo; ids stay strings, as the
// fleet file warns, because they are zero-padded.
const STORES = [
  { id: '0142', name: 'Denver Tech Center' },
  { id: '0311', name: 'Austin Arboretum' },
  { id: '0427', name: 'Boston Seaport' },
  { id: '0519', name: 'Seattle Northgate' },
  { id: '0663', name: 'Chicago Old Orchard' },
  { id: '0788', name: 'Atlanta Perimeter' },
];

// Obviously fictional names, astronomy-flavoured to match the shop. The real checkout
// carries a generated email, but nothing that reads like personal data goes on a screen a
// room full of people is looking at.
const FIRST_NAMES = [
  'Ada', 'Bram', 'Cass', 'Dara', 'Eli', 'Faye', 'Gus', 'Hana',
  'Ilse', 'Jory', 'Kepa', 'Lumi', 'Mira', 'Nils', 'Orin', 'Pell',
];
const LAST_NAMES = [
  'Almeida', 'Brightwater', 'Calder', 'Dunmore', 'Everly', 'Fairbank',
  'Gallant', 'Holloway', 'Ingram', 'Juniper', 'Kestrel', 'Larkspur',
  'Merriwether', 'Northcott', 'Ossler', 'Pemberton',
];

// Insertion-ordered, so the oldest key is the first one out when the buffer is full.
const orders = new Map<string, IPickupOrder>();

// Round-robin rather than a hash of the order id: an even spread means every store in the
// fleet has something to collect, however few orders the shop has placed since a restart.
let nextStore = 0;

/**
 * protos Money -> a number in the same currency, which is all the register displays.
 * `quantity` is here because OrderItem.cost is the *unit* price: checkout multiplies it
 * out itself when it totals an order (prepOrderItems / MultiplySlow in checkout/main.go),
 * so a line total has to be multiplied here too. Rounded once, after multiplying.
 */
function toAmount(money: Money | undefined, quantity = 1): number {
  if (!money) return 0;
  return Math.round((money.units + money.nanos / 1e9) * quantity * 100) / 100;
}

/** Stable per order id, so an order keeps its name across polls of /api/orders/ready. */
function fictionalName(orderId: string): string {
  let hash = 0;
  for (const char of orderId) hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
  return `${FIRST_NAMES[hash % FIRST_NAMES.length]} ${LAST_NAMES[(hash >>> 8) % LAST_NAMES.length]}`;
}

const OrderPickupService = () => ({
  stores: STORES,

  /**
   * Called from the checkout route once an order is placed, so the pickup list is made of
   * real checkouts. Never throws: a bookkeeping failure must not fail a customer's order.
   */
  record(orderId: string, items: IProductCheckoutItem[]): void {
    try {
      if (!orderId || orders.has(orderId)) return;

      const pickupItems: IPickupOrderItem[] = items.map(({ cost, item }) => ({
        productId: item.productId,
        name: item.product.name,
        quantity: item.quantity,
        price: toAmount(cost, item.quantity),
      }));

      const store = STORES[nextStore % STORES.length];
      nextStore += 1;

      orders.set(orderId, {
        orderId,
        placedAt: new Date().toISOString(),
        storeId: store.id,
        status: 'ready',
        customerName: fictionalName(orderId),
        itemCount: pickupItems.reduce((sum, { quantity }) => sum + quantity, 0),
        // Shipping is left out on purpose: a collected order is not shipped, and the item
        // lines summing to the total is what a cashier reads back at the counter.
        total: Math.round(pickupItems.reduce((sum, { price }) => sum + price, 0) * 100) / 100,
        currencyCode: items[0]?.cost?.currencyCode ?? 'USD',
        items: pickupItems,
      });

      if (orders.size > MAX_ORDERS) {
        orders.delete(orders.keys().next().value as string);
      }
    } catch {
      // Swallowed for the reason above; the order itself has already been placed.
    }
  },

  /** Orders still waiting at one store, newest first. */
  listReady(storeId: string): IPickupOrder[] {
    return [...orders.values()]
      .filter(order => order.storeId === storeId && order.status === 'ready')
      .reverse();
  },

  get(orderId: string): IPickupOrder | undefined {
    return orders.get(orderId);
  },

  /** Marks an order collected. Returns undefined if the buffer has never seen it. */
  collect(orderId: string, registerId: string, cashierId: string): IPickupOrder | undefined {
    const order = orders.get(orderId);
    if (!order) return undefined;

    // Collecting twice keeps the first collection: the register shows a 409 with it, which
    // is more useful at a counter than silently reassigning the order to a second cashier.
    if (order.status === 'collected') return order;

    order.status = 'collected';
    order.collectedAt = new Date().toISOString();
    order.registerId = registerId;
    order.cashierId = cashierId;

    return order;
  },
});

export default OrderPickupService();
