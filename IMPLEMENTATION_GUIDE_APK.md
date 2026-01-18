# APK Integration Guide - User Authentication System

## Overview
This guide details the remaining changes needed in the Android APK to complete the user authentication system integration.

## What's Already Done

### 1. API Layer
- ✅ `ApiService.kt` - Added `unique_code` parameter to:
  - `getPlayUrl()`
  - `getVodPlayUrl()`
  - `getSeriesEpisodePlay()`

### 2. Data Layer
- ✅ `PreferencesManager.kt` - Created for storing/retrieving unique code
- ✅ `UniqueCodeSetupScreen.kt` - UI screen for entering unique code

## Required Changes

### 1. MainActivity Integration

Modify `/ellenTV_v1/app/src/main/java/com/reybel/ellentv/MainActivity.kt`:

```kotlin
import com.reybel.ellentv.data.PreferencesManager
import com.reybel.ellentv.ui.setup.UniqueCodeSetupScreen

class MainActivity : ComponentActivity() {
    private lateinit var playerManager: com.reybel.ellentv.ui.player.PlayerManager
    private lateinit var prefsManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onCreate(savedInstanceState)

        prefsManager = PreferencesManager(applicationContext)

        val homeVm: HomeViewModel by viewModels()
        val onDemandVm: OnDemandViewModel by viewModels {
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return OnDemandViewModel(applicationContext) as T
                }
            }
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        playerManager = com.reybel.ellentv.ui.player.PlayerManager(this)

        setContent {
            MaterialTheme(typography = AppTypography) {
                var hasUniqueCode by remember { mutableStateOf(prefsManager.hasUniqueCode()) }

                if (hasUniqueCode) {
                    TvHomeScreen(playerManager, homeVm, onDemandVm, prefsManager)
                } else {
                    UniqueCodeSetupScreen(
                        onCodeSubmitted = { code ->
                            prefsManager.saveUniqueCode(code)
                            hasUniqueCode = true
                        }
                    )
                }
            }
        }
    }
}

// Update TvHomeScreen signature
@Composable
fun TvHomeScreen(
    playerManager: com.reybel.ellentv.ui.player.PlayerManager,
    vm: HomeViewModel,
    onDemandVm: OnDemandViewModel,
    prefsManager: PreferencesManager
) {
    // ... existing code
}
```

### 2. Repository Updates

#### ChannelRepo.kt
Modify `getPlayUrl()` to accept and pass unique code:

```kotlin
class ChannelRepo(private val context: Context) {
    private val api = ApiClient.retrofit.create(ApiService::class.java)
    private val prefsManager = PreferencesManager(context)

    suspend fun getPlayUrl(liveId: String): String {
        val uniqueCode = prefsManager.getUniqueCode()
        val response = api.getPlayUrl(liveId, uniqueCode = uniqueCode)
        return response.url
    }
}
```

#### VodRepo.kt
Modify `getVodPlayUrl()` and `getSeriesEpisodePlay()`:

```kotlin
class VodRepo(private val context: Context) {
    private val api = ApiClient.retrofit.create(ApiService::class.java)
    private val prefsManager = PreferencesManager(context)

    suspend fun getVodPlayUrl(vodId: String, format: String? = null): String {
        val uniqueCode = prefsManager.getUniqueCode()
        val response = api.getVodPlayUrl(vodId, format, uniqueCode = uniqueCode)
        return response.url
    }

    suspend fun getSeriesEpisodePlayUrl(
        providerId: String,
        episodeId: Int,
        format: String? = null
    ): String {
        val uniqueCode = prefsManager.getUniqueCode()
        val response = api.getSeriesEpisodePlay(
            providerId,
            episodeId,
            format,
            uniqueCode = uniqueCode
        )
        return response.url
    }
}
```

### 3. ViewModel Updates

All ViewModels that use the repositories need to pass `applicationContext`:

#### HomeViewModel
```kotlin
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val channelRepo = ChannelRepo(application.applicationContext)
    // ... rest of the code
}
```

#### OnDemandViewModel
Already receives context, just ensure repo has access:
```kotlin
class OnDemandViewModel(private val context: Context) : ViewModel() {
    private val vodRepo = VodRepo(context)
    // ... rest of the code
}
```

#### MoviesViewModel, SeriesViewModel, VodViewModel
Similar changes - ensure they have context and pass it to repos.

### 4. Testing Checklist

After implementing the changes:

1. **First Launch:**
   - [ ] App shows unique code entry screen
   - [ ] Cannot proceed without entering 6-character code
   - [ ] Code is saved after submission

2. **Subsequent Launches:**
   - [ ] App goes directly to main screen (no code entry)
   - [ ] Code is persisted across app restarts

3. **Streaming:**
   - [ ] Live TV channels play correctly with user credentials
   - [ ] VOD movies play correctly
   - [ ] Series episodes play correctly
   - [ ] Alternative streams work (alt1, alt2, alt3)

4. **Error Handling:**
   - [ ] Invalid code shows appropriate error
   - [ ] Disabled user shows "User is disabled" error
   - [ ] Network errors are handled gracefully

5. **Server Side:**
   - [ ] Users tab in Settings shows all users
   - [ ] Can add/edit/delete users
   - [ ] Unique codes are generated automatically
   - [ ] Play button in server UI uses random user

## Additional Notes

### Reset Functionality (Optional)
You may want to add a way to reset the unique code (e.g., in settings or via long press):

```kotlin
// In a settings screen or menu
Button(onClick = {
    prefsManager.clearUniqueCode()
    // Restart app or navigate to setup screen
}) {
    Text("Reset Access Code")
}
```

### Validation Endpoint (Optional)
Consider adding an endpoint to validate the unique code before saving:

```kotlin
@GET("provider-users/by-code/{unique_code}")
suspend fun validateUniqueCode(
    @Path("unique_code") uniqueCode: String
): ProviderUserOut
```

This allows you to show an error immediately if the code doesn't exist.

## Summary

The server-side implementation is complete. The APK needs:
1. MainActivity integration to show setup screen on first launch
2. Repository modifications to pass unique code from PreferencesManager
3. Ensure all ViewModels have access to applicationContext

Once these changes are made, the complete user authentication flow will be operational.
