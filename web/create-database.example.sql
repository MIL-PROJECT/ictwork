-- Run once as a MySQL administrator after choosing an UNUSED schema name.
-- Replace CHANGE_ME_DB_PASSWORD, and use the same password in application.properties.
-- No DROP or overwrite commands are included. Do not import over an existing database.
CREATE DATABASE omnis CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'omnis'@'127.0.0.1' IDENTIFIED BY 'CHANGE_ME_DB_PASSWORD';
GRANT ALL PRIVILEGES ON omnis.* TO 'omnis'@'127.0.0.1';
