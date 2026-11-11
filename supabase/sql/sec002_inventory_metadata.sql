-- SEC-002.1: metadata only. Execute ONLY after explicit read-only inventory approval.
-- No function/view/policy source, passwords, JWT, row identifiers or personal rows returned.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '20s';
SET LOCAL lock_timeout = '2s';
SELECT jsonb_build_object(
 'format','aura-sec002-inventory-v1',
 'server_version_num',current_setting('server_version_num')::integer,
 'server_version',current_setting('server_version'),
 'transaction_read_only',current_setting('transaction_read_only'),
 'visible_pgrst_schemas',current_setting('pgrst.db_schemas',true),
 'schemas',(SELECT coalesce(jsonb_agg(jsonb_build_object('name',nspname,'owner',pg_get_userbyid(nspowner))), '[]'::jsonb)
   FROM pg_namespace WHERE nspname NOT LIKE 'pg_%' AND nspname <> 'information_schema'),
 'roles',(SELECT jsonb_agg(jsonb_build_object('name',rolname,'superuser',rolsuper,'bypassrls',rolbypassrls,
   'inherit',rolinherit,'login',rolcanlogin,'service_member',coalesce(pg_has_role(oid,to_regrole('service_role'),'MEMBER'),false)))
   FROM pg_roles),
 'memberships',(SELECT coalesce(jsonb_agg(jsonb_build_object('role',pg_get_userbyid(roleid),
   'member',pg_get_userbyid(member),'admin',admin_option)), '[]'::jsonb) FROM pg_auth_members),
 'tables',(SELECT coalesce(jsonb_agg(jsonb_build_object('name',c.relname,'kind',c.relkind,'owner',pg_get_userbyid(c.relowner),
   'rls',c.relrowsecurity,'force_rls',c.relforcerowsecurity,'estimated_rows',c.reltuples,
   'authenticated_owner_member',coalesce(pg_has_role(to_regrole('authenticated'),c.relowner,'MEMBER'),false),
   'anon_owner_member',coalesce(pg_has_role(to_regrole('anon'),c.relowner,'MEMBER'),false),
   'executor_select',has_table_privilege(c.oid,'SELECT'),'executor_rls_active',row_security_active(c.oid))), '[]'::jsonb)
   FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p')),
 'columns',(SELECT coalesce(jsonb_agg(jsonb_build_object('table',c.relname,'name',a.attname,
   'type',format_type(a.atttypid,a.atttypmod),'not_null',a.attnotnull,'generated',a.attgenerated)), '[]'::jsonb)
   FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE n.nspname='public' AND c.relkind IN ('r','p') AND a.attnum>0 AND NOT a.attisdropped),
 'policies',(SELECT coalesce(jsonb_agg(jsonb_build_object('table',tablename,'name',policyname,'command',cmd,
   'roles',roles,'permissive',permissive,'using_md5',md5(qual),'check_md5',md5(with_check),
   'using_present',qual IS NOT NULL,'check_present',with_check IS NOT NULL)), '[]'::jsonb)
   FROM pg_policies WHERE schemaname='public'),
 'table_grants',(SELECT coalesce(jsonb_agg(jsonb_build_object('table',c.relname,
   'grantee',CASE WHEN acl.grantee=0 THEN 'PUBLIC' ELSE pg_get_userbyid(acl.grantee) END,
   'privilege',acl.privilege_type,'grantable',acl.is_grantable)), '[]'::jsonb)
   FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
   CROSS JOIN LATERAL aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) acl
   WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m')),
 'column_grants',(SELECT coalesce(jsonb_agg(jsonb_build_object('table',c.relname,'column',a.attname,
   'grantee',CASE WHEN acl.grantee=0 THEN 'PUBLIC' ELSE pg_get_userbyid(acl.grantee) END,
   'privilege',acl.privilege_type,'grantable',acl.is_grantable)), '[]'::jsonb)
   FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
   CROSS JOIN LATERAL aclexplode(a.attacl) acl WHERE n.nspname='public' AND a.attnum>0 AND NOT a.attisdropped),
 'effective_table_privileges',(SELECT coalesce(jsonb_agg(jsonb_build_object('table',c.relname,'role',r.rolname,
   'select',has_table_privilege(r.oid,c.oid,'SELECT'),'insert',has_table_privilege(r.oid,c.oid,'INSERT'),
   'update',has_table_privilege(r.oid,c.oid,'UPDATE'),'delete',has_table_privilege(r.oid,c.oid,'DELETE'),
   'truncate',has_table_privilege(r.oid,c.oid,'TRUNCATE'),
   'column_select',has_any_column_privilege(r.oid,c.oid,'SELECT'))), '[]'::jsonb)
   FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace CROSS JOIN pg_roles r
   WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m') AND r.rolname IN ('anon','authenticated','service_role')),
 'functions',(SELECT coalesce(jsonb_agg(jsonb_build_object('oid',f.oid,'schema',n.nspname,'name',f.proname,
   'arguments',pg_get_function_identity_arguments(f.oid),'result',pg_get_function_result(f.oid),
   'owner',pg_get_userbyid(f.proowner),'definer',f.prosecdef,'kind',f.prokind,'language',l.lanname,
   'body_md5',md5(f.prosrc),'search_path',(SELECT value FROM unnest(f.proconfig) value WHERE value LIKE 'search_path=%'),
   'anon_execute',has_function_privilege('anon',f.oid,'EXECUTE'),
   'authenticated_execute',has_function_privilege('authenticated',f.oid,'EXECUTE'))), '[]'::jsonb)
   FROM pg_proc f JOIN pg_namespace n ON n.oid=f.pronamespace JOIN pg_language l ON l.oid=f.prolang
   WHERE n.nspname='public'),
 'views',(SELECT coalesce(jsonb_agg(jsonb_build_object('name',c.relname,'kind',c.relkind,
   'owner',pg_get_userbyid(c.relowner),'security_invoker',coalesce('security_invoker=true'=ANY(c.reloptions),false),
   'definition_md5',md5(pg_get_viewdef(c.oid)),
   'anon_select',has_table_privilege('anon',c.oid,'SELECT'),
   'authenticated_select',has_table_privilege('authenticated',c.oid,'SELECT'))), '[]'::jsonb)
   FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('v','m')),
 'triggers',(SELECT coalesce(jsonb_agg(jsonb_build_object('table',c.relname,'name',t.tgname,'enabled',t.tgenabled,
   'internal',t.tgisinternal,'function_schema',fn.nspname,'function',f.proname,'definer',f.prosecdef,
   'definition_md5',md5(pg_get_triggerdef(t.oid)))), '[]'::jsonb)
   FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
   JOIN pg_proc f ON f.oid=t.tgfoid JOIN pg_namespace fn ON fn.oid=f.pronamespace WHERE n.nspname='public'),
 'constraints',(SELECT coalesce(jsonb_agg(jsonb_build_object('table',c.relname,'name',con.conname,'type',con.contype,
   'validated',con.convalidated,'deferrable',con.condeferrable,'initially_deferred',con.condeferred,
   'columns',(SELECT jsonb_agg(a.attname ORDER BY u.ord) FROM unnest(con.conkey) WITH ORDINALITY u(num,ord)
     JOIN pg_attribute a ON a.attrelid=con.conrelid AND a.attnum=u.num),
   'referenced_table',con.confrelid::regclass::text,
   'referenced_columns',(SELECT jsonb_agg(a.attname ORDER BY u.ord) FROM unnest(con.confkey) WITH ORDINALITY u(num,ord)
     JOIN pg_attribute a ON a.attrelid=con.confrelid AND a.attnum=u.num),
   'delete_action',con.confdeltype,'definition_md5',md5(pg_get_constraintdef(con.oid)))), '[]'::jsonb)
   FROM pg_constraint con JOIN pg_class c ON c.oid=con.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'),
 'indexes',(SELECT coalesce(jsonb_agg(jsonb_build_object('table',c.relname,'name',i.relname,'unique',ix.indisunique,
   'valid',ix.indisvalid,'ready',ix.indisready,'definition_md5',md5(pg_get_indexdef(i.oid)))), '[]'::jsonb)
   FROM pg_index ix JOIN pg_class c ON c.oid=ix.indrelid JOIN pg_namespace n ON n.oid=c.relnamespace
   JOIN pg_class i ON i.oid=ix.indexrelid WHERE n.nspname='public'),
 'dependencies',(SELECT coalesce(jsonb_agg(jsonb_build_object('kind',d.deptype,
   'object',pg_describe_object(d.classid,d.objid,d.objsubid),
   'referenced',pg_describe_object(d.refclassid,d.refobjid,d.refobjsubid))), '[]'::jsonb)
   FROM pg_depend d WHERE
     (d.classid='pg_class'::regclass AND d.objid IN (SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'))
     OR (d.refclassid='pg_class'::regclass AND d.refobjid IN (SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'))
     OR (d.classid='pg_proc'::regclass AND d.objid IN (SELECT f.oid FROM pg_proc f JOIN pg_namespace n ON n.oid=f.pronamespace WHERE n.nspname='public'))
     OR (d.classid='pg_rewrite'::regclass AND d.objid IN (SELECT rw.oid FROM pg_rewrite rw JOIN pg_class c ON c.oid=rw.ev_class JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public')))
);
ROLLBACK;
