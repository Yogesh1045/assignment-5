-- Assignment 5 test database (local, created only for this assignment). MySQL 8 / MariaDB 10.
CREATE DATABASE IF NOT EXISTS campus;
USE campus;

-- B2: login check (passwords stored in plain text ONLY because this is a deliberately unsafe demo table)
DROP TABLE IF EXISTS users;
CREATE TABLE users (id VARCHAR(20) PRIMARY KEY, pwd VARCHAR(64) NOT NULL, role VARCHAR(20) NOT NULL);
INSERT INTO users VALUES ('admin', 'S3cure!pass', 'ADMIN'), ('192311001', 'arun@123', 'STUDENT');

-- B3 / B4: bus passes
DROP TABLE IF EXISTS bus_pass;
CREATE TABLE bus_pass (
    pass_id   VARCHAR(12)   PRIMARY KEY,
    reg_no    CHAR(9)       NOT NULL,
    name      VARCHAR(60)   NOT NULL,
    route_no  INT           NOT NULL,
    amount    DECIMAL(8,2)  NOT NULL CHECK (amount >= 0),
    campus    VARCHAR(20)   NOT NULL DEFAULT 'MAIN',
    INDEX idx_route (route_no)
);

-- A5: wallets
DROP TABLE IF EXISTS wallet;
CREATE TABLE wallet (owner VARCHAR(20) PRIMARY KEY, kind VARCHAR(10) NOT NULL, balance DECIMAL(10,2) NOT NULL CHECK (balance >= 0));
INSERT INTO wallet VALUES ('192311001-HOSTEL', 'HOSTEL', 2000.00), ('192311001-CANTEEN', 'CANTEEN', 100.00);
