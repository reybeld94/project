# Testing Guide - User Authentication System

## Quick Start

### 1. Restart the Server

```bash
cd /home/user/project/media_server
docker compose down
docker compose up -d
```

Or if using docker-compose v1:
```bash
docker-compose down
docker-compose up -d
```

### 2. Apply Database Migration

```bash
docker compose exec api alembic upgrade head
```

Or:
```bash
docker-compose exec api alembic upgrade head
```

### 3. Check Server Logs

```bash
docker compose logs -f api
```

You should see the server start without errors.

## Testing the Backend

### Step 1: Access the Web UI

1. Open browser to: `http://localhost:8000/ui`
2. Navigate to **Settings → Users** tab
3. Verify the Users interface loads

### Step 2: Add a Provider (if needed)

If you don't have a provider yet:

1. Go to **Settings → Providers**
2. Click **Add Provider**
3. Fill in:
   - **Name**: Test Provider
   - **Base URL**: Your Xtream server URL
   - **Username**: (leave empty - will use users)
   - **Password**: (leave empty - will use users)
4. Save

### Step 3: Create a User

1. Go to **Settings → Users**
2. Click **Add User**
3. Fill in:
   - **Provider**: Select your provider
   - **Alias**: Test User 1
   - **Username**: your_xtream_username
   - **Password**: your_xtream_password
   - **Enabled**: ✓ (checked)
4. Click **Create User**
5. **Note the generated unique code** (e.g., `AB12CD`)

### Step 4: Test the API Directly

#### Test 1: List Users
```bash
curl http://localhost:8000/provider-users | jq
```

Expected: Array of users with unique codes

#### Test 2: Get User by Code
```bash
curl http://localhost:8000/provider-users/by-code/AB12CD | jq
```

Replace `AB12CD` with your generated code.

Expected: User details with username, provider info, etc.

#### Test 3: Test Stream URL with User Code

First, get a live stream ID:
```bash
curl "http://localhost:8000/live?provider_id=YOUR_PROVIDER_ID&limit=1" | jq '.items[0].id'
```

Then test the play URL with your unique code:
```bash
curl "http://localhost:8000/live/STREAM_ID/play?unique_code=AB12CD" | jq
```

Expected:
```json
{
  "id": "...",
  "name": "Channel Name",
  "url": "http://xtream-server/live/your_username/your_password/12345.m3u8"
}
```

Verify the URL contains the username/password from your user, not from the provider.

#### Test 4: Test Without Code (Random User)
```bash
curl "http://localhost:8000/live/STREAM_ID/play" | jq
```

Expected: URL should contain credentials from a random enabled user.

### Step 5: Test User Management

#### Update a User
```bash
curl -X PATCH "http://localhost:8000/provider-users/USER_ID" \
  -H "Content-Type: application/json" \
  -d '{"alias": "Updated Name"}'
```

#### Disable a User
```bash
curl -X PATCH "http://localhost:8000/provider-users/USER_ID" \
  -H "Content-Type: application/json" \
  -d '{"is_enabled": false}'
```

Then try to use the disabled user's code:
```bash
curl "http://localhost:8000/live/STREAM_ID/play?unique_code=AB12CD"
```

Expected: `403 Forbidden - User is disabled`

#### Delete a User
```bash
curl -X DELETE "http://localhost:8000/provider-users/USER_ID"
```

## Testing the Android App (After APK Integration)

### Prerequisite
Complete the APK integration steps in `IMPLEMENTATION_GUIDE_APK.md` first.

### Test Flow

1. **First Launch**
   - ✓ App shows "Welcome to EllenTV" screen
   - ✓ Prompts for 6-character unique code
   - ✓ Cannot proceed without valid code

2. **Enter Unique Code**
   - Enter the code from Step 3 above (e.g., `AB12CD`)
   - ✓ Code is accepted
   - ✓ App proceeds to main screen

3. **Test Streaming**
   - Play a live TV channel
   - ✓ Stream loads successfully
   - ✓ Check server logs - should show request with unique_code parameter

4. **Subsequent Launches**
   - Close and reopen app
   - ✓ Goes directly to main screen (no code entry)
   - ✓ Streams still work

5. **Test Invalid Code**
   - Reset app data or reinstall
   - Try entering invalid code: `INVALID`
   - ✓ Shows error: "User not found"

6. **Test Disabled User**
   - Disable the user in the web UI
   - Try playing a stream in the app
   - ✓ Shows error: "User is disabled"

## Troubleshooting

### Import Error on Startup
If you see:
```
ImportError: cannot import name 'sync_all' from partially initialized module
```

Solution: Already fixed - make sure you have the latest code:
```bash
git pull origin claude/user-auth-system-S8qow
```

### Migration Not Applied
If tables are missing:
```bash
docker compose exec api alembic upgrade head
```

### User Code Not Working
1. Verify user exists and is enabled:
   ```bash
   curl http://localhost:8000/provider-users/by-code/YOUR_CODE
   ```

2. Check server logs:
   ```bash
   docker compose logs -f api
   ```

### No Credentials Available Error
If you see "No credentials available. Please add users to this provider":

1. Make sure you've created at least one user for the provider
2. Verify the user is enabled
3. Or add legacy credentials to the provider itself

## Database Inspection

To inspect the database directly:

```bash
# Connect to database
docker compose exec db psql -U your_db_user -d your_db_name

# List users
SELECT id, provider_id, alias, username, unique_code, is_enabled
FROM provider_users;

# List providers
SELECT id, name, username, password
FROM providers;

# Exit
\q
```

## Success Criteria

✅ Server starts without errors
✅ Can create/edit/delete users in web UI
✅ Unique codes are auto-generated
✅ Stream URLs contain correct user credentials
✅ Disabled users cannot access streams
✅ Random user selection works when no code provided
✅ (After APK integration) App requires code on first launch
✅ (After APK integration) Streams work with user credentials

## Next Steps

Once backend testing is complete:
1. Complete APK integration using `IMPLEMENTATION_GUIDE_APK.md`
2. Test the full end-to-end flow
3. Deploy to production if all tests pass

## Support

If you encounter issues:
1. Check server logs: `docker compose logs -f api`
2. Verify migration applied: `docker compose exec api alembic current`
3. Check database: See "Database Inspection" section above
4. Review the implementation in the committed code
