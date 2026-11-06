-- Run this ONCE in Supabase > SQL Editor. It adds the new update columns and the APK storage bucket.

alter table app_config add column if not exists update_mode     text   default 'button';  -- button | popup | force
alter table app_config add column if not exists apk_sha256      text   default '';
alter table app_config add column if not exists apk_size        bigint default 0;
alter table app_config add column if not exists latest_version_name text default '';
alter table app_config add column if not exists changelog       text   default '';
alter table app_config add column if not exists min_version_code int   default 0;

-- public bucket for APK files (the app downloads from it without logging in)
insert into storage.buckets (id, name, public)
values ('apk', 'apk', true)
on conflict (id) do update set public = true;

-- everybody may download from the bucket; uploads need the service_role key (used only by admin.html)
drop policy if exists "apk public read" on storage.objects;
create policy "apk public read" on storage.objects for select using (bucket_id = 'apk');
