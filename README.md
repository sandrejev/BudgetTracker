# Budget Tracker (Android, Kotlin + Jetpack Compose)

A native Android rewrite of the budget tracker: tracks a monthly spending limit
(default €600), automatically computes the daily allowance as
`monthlyLimit / daysInMonth`, lets you log individual expenses (with optional
notes), delete mistaken entries, browse history by month, and change the
monthly limit in Settings.

## How to open this project

1. Install **Android Studio** (free): https://developer.android.com/studio
2. Open Android Studio → **Open** → select this `BudgetTracker` folder (the one
   containing `settings.gradle.kts`).
3. Let Gradle sync (first time will download Gradle + dependencies — needs
   internet access).
4. Plug in your Android phone via USB with USB debugging enabled (Settings →
   About phone → tap "Build number" 7 times → Developer options → USB
   debugging), or use the built-in emulator.
5. Click the green **Run ▶** button.

No Play Store account or publishing needed — this installs directly as a debug
APK on your device.

## Project structure

```
app/src/main/java/com/example/budgettracker/
  MainActivity.kt              — app entry point + simple screen navigation
  data/
    Expense.kt                 — Room entity (one row per expense)
    ExpenseDao.kt               — Room queries (insert/delete/list)
    AppDatabase.kt              — Room database singleton
    SettingsRepository.kt       — DataStore-backed monthly limit setting
  ui/
    BudgetViewModel.kt          — business logic: month stats, daily rate calc
    Formatting.kt               — €-formatting and date helpers
    MainScreen.kt               — balance + add-expense + this month's entries
    HistoryScreen.kt            — list of all months with balances
    MonthDetailScreen.kt        — single month's entries (with delete)
    SettingsScreen.kt           — edit the monthly limit
    theme/                      — dark theme matching the original web design
```

## Notes

- Data is stored locally on-device (Room database + DataStore), nothing leaves
  your phone.
- Calendar months are used (not rolling 30-day windows), matching the web
  version's final behavior.
- To change the package name/app ID, search-and-replace
  `com.example.budgettracker` and update `applicationId` /  `namespace` in
  `app/build.gradle.kts`.
