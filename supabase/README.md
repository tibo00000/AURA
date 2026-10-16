# Supabase Configuration

Configuration for Supabase database and migrations.

## Structure

```
supabase/
├── migrations/
│   ├── 20260418_create_catalog_tables.sql
│   └── 20260418_create_mapping_tables.sql
├── package.json
└── config.toml
```

## Usage

The Supabase CLI is installed locally in this directory. Use `npx supabase`
from `supabase/`; do not install it globally.

```bash
cd supabase
npx supabase --version
```

### Link project
```bash
npx supabase link --project-ref YOUR_PROJECT_REF
```

### Push migrations
```bash
npx supabase db push
```

### Pull remote schema
```bash
npx supabase db pull
```

## Migrations

All new Supabase schema migrations are timestamped SQL files in `migrations/`.
Before a remote migration, inspect the target with `npx supabase migration list`
and obtain the explicit approval required by `docs/architecture/engineering-rules.md`.
Supabase CLI automatically applies recorded migrations in order.
