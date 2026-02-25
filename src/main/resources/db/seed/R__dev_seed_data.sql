-- Repeatable migration: Dev seed data (only loaded via dev profile)
-- This file is NOT included in production Flyway locations.

INSERT INTO accounts (id, email, name, phone, company, identity_provider_user_id, is_active, created_at, updated_at)
VALUES
    ('c823d8b8-0e6f-4242-a350-d6ef335ab4e8', 'pliss.boris@gmail.com', 'Pliss Boris', '+972524411488', 'Best Garage', 'auth0|692c4e40efbb43540f5ca5f9', true, '2025-11-30 14:01:36.599426', '2025-11-30 14:01:36.599426'),
    ('1c3391cc-6b09-4d6a-b27b-5c2d9e66d710', 'daniel@bitbi.io', 'Daniel Goldberg', '0542265158', NULL, 'auth0|693154df0c5fbfb936635b51', true, '2025-12-04 09:31:11.958543', '2025-12-04 09:31:11.958543'),
    ('4852a65b-db11-4314-bf80-7fde53dd6195', 'victor@bitbi.io', 'Victor Kelevra', '+972548779885', NULL, 'auth0|6931877a7a7961569315dd53', true, '2025-12-04 13:07:06.725032', '2025-12-04 13:07:06.725032'),
    ('a1cc5720-a7cc-4010-8eed-e21e5abe836e', 'anna@bitbi.io', 'Anna Gurevich', '+972508333093', NULL, 'auth0|693189430c5fbfb936636f55', true, '2025-12-04 13:14:43.563234', '2025-12-04 13:14:43.563234'),
    ('e508082f-1b73-40da-a3b0-f637c0c0f9ef', 'ania.gurevich@gmail.com', 'Anna Gurevich', '+972508333093', 'BitSmart', 'auth0|69318a3faa7c651ca90f7b36', true, '2025-12-04 13:18:55.788376', '2025-12-04 13:18:55.788376'),
    ('470a25cd-afe6-4d47-a10d-e0b1d0763ec3', 'vic4040@me.com', 'Vic', '+9720548780181', 'Acme', 'auth0|69318de9aa7c651ca90f7d18', true, '2025-12-04 13:34:33.335785', '2025-12-04 13:34:33.335785'),
    ('4fae836b-81f0-473f-8d7c-918ce64f4eab', 'nataliiakovalik@gmail.com', 'Nataliia Saldugina', '0532280155', 'Best Garage', 'auth0|696b7212df86accd4995355f', true, '2026-01-17 11:27:14.116117', '2026-01-17 11:27:14.116117'),
    ('f7997821-0182-4b2d-be61-befc8b40fde0', 'eitan@danycom.co.il', 'Eitan Dany', '07722520', 'Dany Com', 'auth0|696cd9e14edbeb8126b058f5', true, '2026-01-18 13:02:25.793264', '2026-01-18 13:02:25.793264'),
    ('07f4f214-0737-4523-9df7-1aec312ed6d4', 'musahroshel@gmail.com', 'Roshel Agaev', '0548948202', 'מוסך רושל ב״מ', 'auth0|6970c38de83daa1c8d690e1c', true, '2026-01-21 12:16:13.467273', '2026-01-21 12:16:13.467273'),
    ('3a7d4a99-ccf9-4471-8293-3bc82742d9df', 'misgav.ru@gmail.com', 'Misgav Rubinstein', '0546366570', 'Eden', 'auth0|697b6e92cf1b7726190e9fd1', true, '2026-01-29 14:28:35.085145', '2026-01-29 14:28:35.085145'),
    ('5bffaaa1-f5a1-4771-9c3a-dc9b2ba78df0', 'dtmobil14@gmail.com', 'דנה תותיה', '+972546693512', 'GesherGolan', 'auth0|69889a9b0f3efbbdb6c3b11e', true, '2026-02-08 14:15:55.810768', '2026-02-08 14:15:55.810768')
ON CONFLICT (id) DO UPDATE SET
    email = EXCLUDED.email,
    name = EXCLUDED.name,
    phone = EXCLUDED.phone,
    company = EXCLUDED.company,
    identity_provider_user_id = EXCLUDED.identity_provider_user_id,
    is_active = EXCLUDED.is_active,
    updated_at = EXCLUDED.updated_at;