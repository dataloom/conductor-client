-- Enables parameterization of user creation code in terms of username
-- and credential. When concatenating it is key to use quote_ident and
-- quote_literal to avoid inject from user provided values.
CREATE OR REPLACE FUNCTION create_ol_user(id text, cred text)
RETURNS boolean AS $$
declare t_is_role boolean;
BEGIN
  PERFORM rolname FROM pg_roles where rolname = id;
  t_is_role := found;
  if not t_is_role then
  	execute  'CREATE USER ' || quote_ident( id ) || ' with password ' || quote_literal( cred );
    return true;
  end if;

  return false;
END
$$ LANGUAGE plpgsql;