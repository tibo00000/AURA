-- Execute inside the authorized READ ONLY snapshot, or in the isolated restore.
-- Result is PRIVATE: encrypt it, never print object definitions/ACL or row proofs.
SELECT jsonb_build_object(
 'database',(SELECT jsonb_build_object('encoding',pg_encoding_to_char(encoding),'collate',datcollate,
   'ctype',datctype,'provider',datlocprovider,'locale',datlocale,'collation_version',datcollversion,
   'actual_version',pg_database_collation_actual_version(oid),'icu_rules',daticurules)
   FROM pg_database WHERE datname=current_database()),
 'schemas',(SELECT jsonb_agg(jsonb_build_object('name',nspname,'owner',pg_get_userbyid(nspowner),'acl',
   (SELECT jsonb_agg(v::text ORDER BY v::text) FROM unnest(coalesce(nspacl,acldefault('n',nspowner))) v)) ORDER BY nspname)
   FROM pg_namespace WHERE nspname IN('public','auth','supabase_migrations')),
 'relations',(SELECT jsonb_agg(jsonb_build_object('schema',n.nspname,'name',c.relname,'kind',c.relkind,
   'owner',pg_get_userbyid(c.relowner),'rls',c.relrowsecurity,'force_rls',c.relforcerowsecurity,
   'options',c.reloptions,'partition',c.relispartition,'acl',(SELECT jsonb_agg(v::text ORDER BY v::text) FROM unnest(coalesce(c.relacl,acldefault(CASE WHEN c.relkind='S' THEN 'S'::"char" ELSE 'r'::"char" END,c.relowner))) v))
   ORDER BY n.nspname,c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE n.nspname IN('public','auth','supabase_migrations') AND c.relkind IN('r','p','S','v','m')),
 'columns',(SELECT jsonb_agg(jsonb_build_object('schema',n.nspname,'table',c.relname,'name',a.attname,
   'position',a.attnum,'type',format_type(a.atttypid,a.atttypmod),'notnull',a.attnotnull,
   'identity',a.attidentity,'generated',a.attgenerated,'default',pg_get_expr(d.adbin,d.adrelid),
   'acl',(SELECT jsonb_agg(v::text ORDER BY v::text) FROM unnest(a.attacl) v))
   ORDER BY n.nspname,c.relname,a.attnum) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
   JOIN pg_namespace n ON n.oid=c.relnamespace LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
   WHERE n.nspname IN('public','auth','supabase_migrations') AND c.relkind IN('r','p','v','m') AND a.attnum>0 AND NOT a.attisdropped),
 'constraints',(SELECT jsonb_agg(jsonb_build_object('schema',n.nspname,'table',c.relname,'name',k.conname,
   'definition',pg_get_constraintdef(k.oid,true),'validated',k.convalidated,'deferrable',k.condeferrable,'deferred',k.condeferred)
   ORDER BY n.nspname,c.relname,k.conname) FROM pg_constraint k JOIN pg_namespace n ON n.oid=k.connamespace
   LEFT JOIN pg_class c ON c.oid=k.conrelid WHERE n.nspname IN('public','auth','supabase_migrations')),
 'indexes',(SELECT jsonb_agg(jsonb_build_object('schema',n.nspname,'table',c.relname,'name',i.relname,
   'definition',pg_get_indexdef(x.indexrelid),'valid',x.indisvalid,'ready',x.indisready)
   ORDER BY n.nspname,c.relname,i.relname) FROM pg_index x JOIN pg_class c ON c.oid=x.indrelid
   JOIN pg_class i ON i.oid=x.indexrelid JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE n.nspname IN('public','auth','supabase_migrations')),
 'policies',(SELECT jsonb_agg(jsonb_build_object('schema',n.nspname,'table',c.relname,'name',p.polname,
   'command',p.polcmd,'permissive',p.polpermissive,'using',pg_get_expr(p.polqual,p.polrelid),
   'check',pg_get_expr(p.polwithcheck,p.polrelid),'roles',(SELECT jsonb_agg(CASE WHEN v=0 THEN 'PUBLIC' ELSE pg_get_userbyid(v) END ORDER BY v=0,pg_get_userbyid(v)) FROM unnest(p.polroles) v))
   ORDER BY n.nspname,c.relname,p.polname) FROM pg_policy p JOIN pg_class c ON c.oid=p.polrelid
   JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN('public','auth','supabase_migrations')),
 'functions',(SELECT jsonb_agg(jsonb_build_object('schema',n.nspname,'name',p.proname,
   'signature',pg_get_function_identity_arguments(p.oid),'owner',pg_get_userbyid(p.proowner),
   'definition',pg_get_functiondef(p.oid),'acl',(SELECT jsonb_agg(v::text ORDER BY v::text) FROM unnest(coalesce(p.proacl,acldefault('f',p.proowner))) v))
   ORDER BY n.nspname,p.proname,pg_get_function_identity_arguments(p.oid)) FROM pg_proc p
   JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN('public','auth','supabase_migrations') AND p.prokind IN('f','p')),
 'triggers',(SELECT jsonb_agg(jsonb_build_object('schema',n.nspname,'table',c.relname,'name',t.tgname,
   'enabled',t.tgenabled,'definition',pg_get_triggerdef(t.oid,true)) ORDER BY n.nspname,c.relname,t.tgname)
   FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE NOT t.tgisinternal AND n.nspname IN('public','auth','supabase_migrations')),
 'default_acl',(SELECT jsonb_agg(jsonb_build_object('schema',n.nspname,'owner',pg_get_userbyid(d.defaclrole),
   'kind',d.defaclobjtype,'acl',(SELECT jsonb_agg(v::text ORDER BY v::text) FROM unnest(d.defaclacl) v))
   ORDER BY n.nspname,pg_get_userbyid(d.defaclrole),d.defaclobjtype) FROM pg_default_acl d
   JOIN pg_namespace n ON n.oid=d.defaclnamespace WHERE n.nspname IN('public','auth','supabase_migrations')),
 'types',(SELECT jsonb_agg(jsonb_build_object('schema',n.nspname,'name',t.typname,'kind',t.typtype,
   'base',format_type(t.typbasetype,t.typtypmod),'notnull',t.typnotnull,'default',t.typdefault,
   'enum',(SELECT jsonb_agg(enumlabel ORDER BY enumsortorder) FROM pg_enum e WHERE e.enumtypid=t.oid))
   ORDER BY n.nspname,t.typname) FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace
   WHERE n.nspname IN('public','auth','supabase_migrations') AND t.typtype IN('e','d')),
 'sequences',(SELECT jsonb_agg(jsonb_build_object('schema',n.nspname,'name',c.relname,
   'start',s.seqstart,'increment',s.seqincrement,'min',s.seqmin,'max',s.seqmax,'cache',s.seqcache,'cycle',s.seqcycle)
   ORDER BY n.nspname,c.relname) FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid
   JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN('public','auth','supabase_migrations')),
 'client_roles',(SELECT jsonb_agg(jsonb_build_object('name',rolname,'superuser',rolsuper,'bypass_rls',rolbypassrls)
   ORDER BY rolname) FROM pg_roles WHERE rolname IN('anon','authenticated','service_role')),
 'required_extensions',(SELECT jsonb_agg(jsonb_build_object('name',e.extname,'version',e.extversion,'schema',n.nspname)
   ORDER BY e.extname) FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace WHERE e.extname IN('pgcrypto','uuid-ossp'))
);
