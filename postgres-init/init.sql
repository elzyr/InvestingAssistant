SELECT 'CREATE DATABASE investing_assistant'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'investing_assistant')\gexec

SELECT 'CREATE DATABASE instrument_db'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'instrument_db')\gexec
