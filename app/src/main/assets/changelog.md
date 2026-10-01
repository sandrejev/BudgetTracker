## v1.3.0 — 2026-09-30 — Common Names & LLM Integration

- LLM-powered common name normalizer: raw receipt codes (e.g. "MLCH WHOL 3.25% 2L") resolved to readable labels ("whole milk")
- Common name database (common_names table) stores and reuses resolved names across all receipts
- LLM prefers names already in the database for consistency; proposes new ones otherwise
- ✨ button in receipt review screen resolves names before saving
- ✨ button in receipt detail screen resolves names for existing stored items
- Common name shown below raw item name in both review and detail screens
- Gemini API endpoint updated to gemini-3.8-flash (free tier)
- Reprocess dialog passes through existing common names correctly

## v1.2.0 — 2026-09-28 — Receipt Pipeline

- Share receipt images (PNG/JPG) or PDFs directly into the app
- MLKit OCR for images; pdfbox for PDF text extraction
- Level 0 intermediate JSON representation preserves token positions
- Processor configs are JSON files, not code — swap them without rebuilding
- Built-in processors for Lidl, Müller, Rewe and Penny
- Auto-select processor by shop name when adding a receipt
- LLM-assisted processor generation (Gemini free tier by default)
- Configurable LLM API key and endpoint URL in Settings
- Editable receipt review table — fix item names and prices before saving
- Comma and period both work as decimal separators (German locale support)
- Receipt detail view for any past expense — edit or delete individual items
- Reprocess any stored receipt with a different processor
- Manage processors screen — view JSON, import from clipboard, delete, copy LLM prompt
- Export and import all data (expenses, receipts, shops) as a single JSON file

## v1.1.0 — 2026-09 — Shop Management

- Shop management screen — add, edit and delete shops
- Shop logos fetched automatically via Clearbit
- Pin shop locations on an osmdroid map (no API key required)
- Multiple locations per shop for multi-branch stores
- GPS-based nearby-shop detection when logging an expense
- Shop name shown on every expense row and in month detail

## v1.0.0 — 2026-09 — Initial Release

- Monthly budget limit with per-day allowance calculation
- Balance = days elapsed × daily rate − total spent
- Add expenses manually with an amount and optional note
- Daily spending bar chart with tap-to-expand day
- Month history screen listing all recorded months
- Month detail with collapsible day groups
- Edit and delete individual expenses
- Clear all entries for the current month
