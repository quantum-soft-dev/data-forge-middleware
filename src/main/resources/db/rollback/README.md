# Database Migration Rollback Scripts

This directory contains rollback scripts for database migrations.

## Important Notes

⚠️ **Flyway Community Edition does NOT support automatic rollback.**

Rollback is a commercial feature available only in Flyway Teams/Enterprise editions.
These scripts are provided for **manual rollback** if needed in emergency situations.

## Usage

### Prerequisites

1. **Always create a database backup before running rollback scripts**
   ```bash
   pg_dump -U postgres -d dfm > backup_$(date +%Y%m%d_%H%M%S).sql
   ```

2. **Stop the application** to prevent concurrent database access

3. **Verify the rollback script** matches the migration you want to undo

### Running a Rollback Script

```bash
# Connect to the database
psql -U postgres -d dfm

# OR execute the script directly
psql -U postgres -d dfm -f src/main/resources/db/migration/rollback/V9__rollback_phone_and_company.sql
```

### Update Flyway Schema History

After running the rollback script, you must update the `flyway_schema_history` table
to remove the migration entry:

```sql
-- View current migration history
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC;

-- Remove the specific version entry (CAUTION!)
DELETE FROM flyway_schema_history WHERE version = '9';
```

⚠️ **Warning:** Deleting from `flyway_schema_history` can cause Flyway to re-run migrations.
Only do this if you're absolutely sure and have a backup.

## Alternative: Forward-Only Migrations

Instead of rollback, consider creating a new forward migration to undo changes:

```sql
-- V10__remove_phone_and_company.sql
ALTER TABLE accounts
    DROP COLUMN IF EXISTS phone,
    DROP COLUMN IF EXISTS company;
```

This approach:
- ✅ Works with Flyway Community Edition
- ✅ Preserves migration history
- ✅ Follows "forward-only" migration best practice

## Available Rollback Scripts

| Version | Description | Rollback Script |
|---------|-------------|-----------------|
| V9 | Add phone and company to accounts | V9__rollback_phone_and_company.sql |

## Rollback Checklist

- [ ] Database backup created
- [ ] Application stopped
- [ ] Rollback script reviewed
- [ ] Team notified (production only)
- [ ] Rollback script executed
- [ ] flyway_schema_history updated
- [ ] Application restarted and tested
- [ ] Backup can be deleted (after verification)

## Getting Help

If you need to rollback a migration in production:

1. Contact the database administrator
2. Review the backup and rollback procedure
3. Test the rollback in a staging environment first
4. Document the reason for rollback in change log

## References

- [Flyway Documentation](https://flywaydb.org/documentation/)
- [Flyway Undo Migrations (Teams Edition)](https://flywaydb.org/documentation/concepts/migrations#undo-migrations)
