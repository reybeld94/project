-- SQL script to manually add epg_time_offset column to live_streams table
-- This is a fallback if Alembic migration cannot be run

-- Add the column
ALTER TABLE live_streams ADD COLUMN IF NOT EXISTS epg_time_offset INTEGER;

-- Verify the column was added
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_name = 'live_streams'
  AND column_name = 'epg_time_offset';

-- Optional: Check if there are any rows to update (there shouldn't be any with values yet)
SELECT COUNT(*) as total_channels,
       COUNT(epg_time_offset) as channels_with_offset
FROM live_streams;
