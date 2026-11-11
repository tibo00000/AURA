BEGIN READ ONLY;
SET LOCAL statement_timeout='15s';
SELECT jsonb_build_object(
'server_version',current_setting('server_version'),
'connection_role',current_user,
'role_attributes',(SELECT jsonb_build_object('superuser',rolsuper,'bypass_rls',rolbypassrls,'create_role',rolcreaterole,'create_db',rolcreatedb) FROM pg_roles WHERE rolname=current_user),
'schemas',(SELECT jsonb_agg(nspname ORDER BY nspname) FROM pg_namespace WHERE nspname NOT LIKE 'pg_%' AND nspname<>'information_schema'),
'extensions',(SELECT jsonb_agg(jsonb_build_object('name',e.extname,'version',e.extversion,'schema',n.nspname,'config_relations',(SELECT jsonb_agg(c.oid::regclass::text ORDER BY c.oid::regclass::text) FROM pg_class c WHERE c.oid=ANY(e.extconfig))) ORDER BY e.extname) FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace),
'relations_by_schema',(SELECT jsonb_agg(v ORDER BY v->>'schema') FROM (
 SELECT jsonb_build_object('schema',n.nspname,'tables',count(*) FILTER(WHERE c.relkind IN('r','p')),
 'missing_select',count(*) FILTER(WHERE c.relkind IN('r','p') AND NOT has_table_privilege(c.oid,'SELECT')),
 'rls_without_direct_bypass',count(*) FILTER(WHERE c.relkind IN('r','p') AND c.relrowsecurity AND NOT((SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user) OR (pg_has_role(c.relowner,'USAGE') AND NOT c.relforcerowsecurity))),
 'sequences',count(*) FILTER(WHERE c.relkind='S'),'foreign_tables',count(*) FILTER(WHERE c.relkind='f')) v
 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema'
 GROUP BY n.nspname) s),
'roles',(SELECT jsonb_agg(jsonb_build_object('name',rolname,'superuser',rolsuper,'bypass_rls',rolbypassrls,'login',rolcanlogin,'create_role',rolcreaterole) ORDER BY rolname) FROM pg_roles WHERE rolname NOT LIKE 'pg_%'),
'memberships',(SELECT jsonb_agg(jsonb_build_object('role',r.rolname,'member',m.rolname,'admin',a.admin_option,'inherit',a.inherit_option,'set',a.set_option) ORDER BY r.rolname,m.rolname) FROM pg_auth_members a JOIN pg_roles r ON r.oid=a.roleid JOIN pg_roles m ON m.oid=a.member),
'public_auth_cross_dependencies',(SELECT jsonb_agg(DISTINCT jsonb_build_object('source_schema',ns.nspname,'target_schema',nr.nspname,'target_kind',d.refclassid::regclass::text)) FROM pg_depend d JOIN pg_class c ON d.classid='pg_class'::regclass AND c.oid=d.objid JOIN pg_namespace ns ON ns.oid=c.relnamespace JOIN pg_class rc ON d.refclassid='pg_class'::regclass AND rc.oid=d.refobjid JOIN pg_namespace nr ON nr.oid=rc.relnamespace WHERE ns.nspname IN('public','auth') AND nr.nspname NOT IN('public','auth','pg_catalog')),
'large_objects',(SELECT count(*) FROM pg_largeobject_metadata),
'publications',(SELECT count(*) FROM pg_publication),
'subscriptions',(SELECT count(*) FROM pg_subscription),
'foreign_servers',(SELECT count(*) FROM pg_foreign_server),
'event_triggers',(SELECT count(*) FROM pg_event_trigger)
);
ROLLBACK;

-- Grantors are catalog metadata, never role passwords/configuration values.
BEGIN READ ONLY;
SELECT r.rolname AS role, m.rolname AS member, g.rolname AS grantor,
       a.admin_option, a.inherit_option, a.set_option
FROM pg_auth_members a
JOIN pg_roles r ON r.oid=a.roleid
JOIN pg_roles m ON m.oid=a.member
JOIN pg_roles g ON g.oid=a.grantor
ORDER BY r.rolname,m.rolname;
ROLLBACK;

BEGIN READ ONLY; SET LOCAL statement_timeout='15s';
SELECT jsonb_build_object(
'roles_catalog_readable',has_table_privilege('pg_catalog.pg_roles','SELECT'),
'membership_catalog_readable',has_table_privilege('pg_catalog.pg_auth_members','SELECT'),
'sequences_missing_select',(SELECT count(*) FROM pg_class c JOIN pg_namespace n ON c.relnamespace=n.oid WHERE n.nspname NOT LIKE 'pg_%' AND CASE WHEN c.relkind='S' THEN NOT has_sequence_privilege(c.oid,'SELECT') ELSE false END),
'dependency_schema_edges',(SELECT jsonb_agg(v ORDER BY v->>'source',v->>'target') FROM(
 SELECT jsonb_build_object('source',s.schema,'target',t.schema,'objects',count(*)) v FROM pg_depend d
 CROSS JOIN LATERAL pg_identify_object(d.classid,d.objid,d.objsubid) s
 CROSS JOIN LATERAL pg_identify_object(d.refclassid,d.refobjid,d.refobjsubid) t
 WHERE s.schema IN('public','auth') AND t.schema IS NOT NULL AND t.schema NOT IN('public','auth','pg_catalog')
 GROUP BY s.schema,t.schema) x),
'extension_routines_public_auth',(SELECT count(*) FROM pg_depend d JOIN pg_proc p ON d.classid='pg_proc'::regclass AND d.objid=p.oid JOIN pg_namespace n ON p.pronamespace=n.oid WHERE d.deptype='e' AND n.nspname IN('public','auth')),
'event_trigger_owners',(SELECT jsonb_agg(jsonb_build_object('owner',pg_get_userbyid(e.evtowner),'enabled',e.evtenabled,'function_schema',n.nspname) ORDER BY n.nspname,e.evtname) FROM pg_event_trigger e JOIN pg_proc p ON e.evtfoid=p.oid JOIN pg_namespace n ON p.pronamespace=n.oid)
);
ROLLBACK;
