# Instrucciones para Ejecutar la Migración EPG Time Offset

La funcionalidad de ajuste de tiempo EPG requiere agregar una nueva columna a la base de datos.

## Error Actual
```
psycopg2.errors.UndefinedColumn: column live_streams.epg_time_offset does not exist
```

## Solución

Tienes **3 opciones** para ejecutar la migración:

---

### Opción 1: Usando Docker Compose (RECOMENDADO)

Ejecuta el siguiente comando desde el directorio `media_server/`:

```bash
docker-compose exec api alembic upgrade head
```

Si los contenedores están corriendo con un prefijo diferente (ej: `mediaserver-api-1`), usa:

```bash
docker exec -it mediaserver-api-1 alembic upgrade head
```

**Verifica que se aplicó correctamente:**
```bash
docker-compose exec api alembic current
```

Deberías ver: `a7b8c9d0e1f2 (head)`

---

### Opción 2: Ejecutar Script Bash dentro del Contenedor

```bash
chmod +x run_migration.sh
docker cp run_migration.sh mediaserver-api-1:/tmp/
docker exec -it mediaserver-api-1 bash /tmp/run_migration.sh
```

---

### Opción 3: SQL Manual (Si Docker no funciona)

Conecta a la base de datos PostgreSQL y ejecuta:

```bash
# Conectar a PostgreSQL
docker-compose exec db psql -U <POSTGRES_USER> -d <POSTGRES_DB>

# O si conoces las credenciales:
psql -h localhost -p 5433 -U <POSTGRES_USER> -d <POSTGRES_DB>
```

Luego ejecuta el SQL:

```sql
ALTER TABLE live_streams ADD COLUMN IF NOT EXISTS epg_time_offset INTEGER;
```

O usa el archivo SQL provisto:

```bash
docker cp add_epg_time_offset_column.sql mediaserver-db-1:/tmp/
docker exec -it mediaserver-db-1 psql -U <POSTGRES_USER> -d <POSTGRES_DB> -f /tmp/add_epg_time_offset_column.sql
```

---

## Verificación

Después de ejecutar la migración, **reinicia el contenedor de la API**:

```bash
docker-compose restart api
```

Luego recarga la página de EPG en tu navegador. El error debería desaparecer y verás el nuevo campo "Ajuste: +/-HHMM".

---

## Notas

- La migración es **segura** y **reversible**
- Solo agrega una columna nueva, no modifica datos existentes
- La columna acepta valores NULL por defecto
- Rango permitido: -720 a +720 minutos (-12h a +12h)

---

## En caso de problemas

Si después de migrar sigues viendo el error:

1. Verifica que la columna existe:
   ```sql
   \d live_streams
   ```

2. Reinicia completamente los contenedores:
   ```bash
   docker-compose down
   docker-compose up -d
   ```

3. Revisa los logs:
   ```bash
   docker-compose logs -f api
   ```
