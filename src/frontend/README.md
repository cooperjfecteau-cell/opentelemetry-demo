# Frontend service

The frontend is a [Next.js](https://nextjs.org/) application that is composed
by two layers.

1. Client side application. Which renders the components for the OTEL webstore.
2. API layer. Connects the client to the backend services by exposing REST endpoints.

## Build Locally

By running `docker compose up` at the root of the project you'll have access to
the frontend client by going to <http://localhost:8080/>.

## Local development

Currently, the easiest way to run the frontend for local development is to execute

```shell
docker compose run --service-ports -e NODE_ENV=development --volume $(pwd)/src/frontend:/app --volume $(pwd)/pb:/app/pb --user node --entrypoint sh frontend
```

from the root folder.

It will start all of the required backend services
and within the container simply run `npm run dev`.
After that the app should be available at <http://localhost:8080/>.

## Order pickup API

Three routes serve buy-online-pickup-in-store to the in-store register
(bluebox-demo#50):

```
GET  /api/orders/ready?storeId=0142   -> { orders: [ ... ] }
GET  /api/orders/{orderId}            -> one order, status ready | collected | unknown
POST /api/orders/{orderId}/collect    -> body { registerId, cashierId }
```

The orders are real: `POST /api/checkout` hands each order it places to
`services/OrderPickup.service.ts`, which keeps the **last 200 in this process's
memory** and deals them round-robin across the six stores in the register fleet.
There is no database, so **restarting the frontend loses every pending order** -
the load generator refills the buffer within a minute. Customer names are
generated from a fixed fictional list, never from the checkout's email.

`total` and each item's `price` are plain numbers in `currencyCode`; `price` is
the line total the checkout service computed, so the item lines sum to `total`.
The load generator shops in several currencies, so `currencyCode` is not always
USD and the register has to render it rather than assume dollars.
Shipping is left out because a collected order is not shipped. An order the
buffer no longer holds comes back as `status: "unknown"` with HTTP 200;
collecting one twice returns 409 with the first collection.
