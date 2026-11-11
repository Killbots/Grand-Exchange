# Grand-Exchange

GrandExchange is a Paper plugin for player-to-player fixed-price listings with
inventory menus, baseline pricing, and shared MariaDB storage for multi-server
networks.

The GrandExchange source code is licensed under Apache License 2.0. Third-party
libraries bundled in the plugin keep their own licenses; see `NOTICE` and
`licenses/` for details.

> **Alpha/Beta software:** This project is still under active development.
> Features, configuration, and database schemas may change between builds.
> Test with backups before using it for a live economy.

## Features

- Browse material categories and sell listings in an inventory GUI.
- Create several sell offers in one batch by dragging stacks into the 36-slot
  sell chest. Each stack becomes a separate offer.
- Use baseline prices, adjust them by 5%, or enter a custom unit price in chat.
- Manage active offer slots with configurable permission tiers.
- Queue seller payouts while the seller is offline.
- Record the origin backend for offers and the backend where trades complete.
- Use H2 for a local market or MariaDB for a shared market.
- Customize the inventory title with `menu.name`, standard `&` color codes, and
  hex gradients such as `&#54DAF4`.
- Reload live settings and data files with `/exchange reload` (admin).

## Build

Use Java 21 and Gradle:

```sh
gradle shadowJar
```

The plugin jar is written to `build/libs/GrandExchange-0.1.0-SNAPSHOT.jar`.

## Install

1. Install the jar on each Paper backend that should participate in the market.
2. Install Vault and a Vault-compatible economy provider on each backend.
3. Configure one unique `server-id` per backend.
4. For a shared market, set every backend to the same reachable MariaDB host,
   database, username, and password, then restart each backend.

Do not install this Paper plugin on a Velocity proxy. Keep database credentials
in each server's `plugins/GrandExchange/config.yml`; do not commit production
configuration files or credentials to a public repository.

## Storage and server IDs

Use `storage.type: h2` for a single-server market. For a network market, use
`storage.type: mariadb` and the same database on every backend. The `server-id`
value is stored as `origin_server` on new offers and `transaction_server` on
completed trades. Existing database rows receive `unknown` during the schema
migration.

Example backend IDs are `hub`, `towny`, `survival`, and `resource`. IDs must be
1–32 lowercase letters, numbers, underscores, or hyphens, and must be unique
within the network.

## Commands and permissions

- `/ge` opens the menu; `/ge sell <unit-price> [amount]` creates an
  offer from the item in hand.
- `/ge orders`, `/ge buy <listing-id>`, and `/ge cancel <listing-id>` manage
  listings.
- `/ge baseline` and `/ge custom` manage prices and custom items.
- `/ge reload` reloads `config.yml`, `baseline-prices.yml`,
  `shop-categories.yml`, and `custom-items.yml` without restarting. Changes to
  database settings or `server-id` still need a restart.
- `grandexchange.use` allows market use; `grandexchange.admin` allows price and
  catalog administration.
- `grandexchange.slots.10`, `.12`, `.16`, `.20`, `.32`, and `.40` unlock higher
  active-offer limits. The configured default is eight.

## Dependencies

Paper 1.21.11, Java 21, Vault, and a Vault-compatible economy provider are
required. GrandExchange will not enable without Vault and an active economy
provider. H2 and MariaDB JDBC drivers are bundled in the jar.
