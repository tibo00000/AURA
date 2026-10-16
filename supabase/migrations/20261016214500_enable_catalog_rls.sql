-- Protect the server-authoritative music catalog from direct PostgREST/GraphQL access.
-- The AURA backend accesses these tables with the Supabase service_role key.

ALTER TABLE public.artists ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.albums ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tracks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.track_source_links ENABLE ROW LEVEL SECURITY;

-- Catalog access is mediated exclusively by the AURA backend.  Removing the
-- grants also prevents schema discovery through PostgREST and pg_graphql.
REVOKE ALL PRIVILEGES ON TABLE public.artists, public.albums, public.tracks,
    public.track_source_links FROM anon, authenticated;
