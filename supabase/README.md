# Supabase Configuration

Configuration for Supabase database and migrations.

## Structure

```
supabase/
├── migrations/
│   ├── 20260418_create_catalog_tables.sql
│   └── 20260418_create_mapping_tables.sql
└── config.toml
```

## Usage

### Link project
```bash
supabase link --project-ref YOUR_PROJECT_REF
```

### Push migrations
```bash
supabase db push
```

### Pull remote schema
```bash
supabase db pull
```

## Migrations

All migrations are timestamped SQL files in `migrations/` directory.
Supabase CLI automatically applies them in order.
