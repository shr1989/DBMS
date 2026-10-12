-- Hotel Management System — starting records
-- These are the five rows per table inserted in the project report (section 7.2).
-- setup.sh loads this file only when the database is completely empty.

INSERT INTO GUEST VALUES
(1, 'Rahul Sharma', '9876543210', 'rahul@gmail.com', 'MG Road', 'Bangalore', 'AADHAR001'),
(2, 'Priya Nair', '9876543211', 'priya@gmail.com', 'Anna Nagar', 'Chennai', 'AADHAR002'),
(3, 'Arjun Mehta', '9876543212', 'arjun@gmail.com', 'Kothrud', 'Pune', 'AADHAR003'),
(4, 'Sneha Reddy', '9876543213', 'sneha@gmail.com', 'Banjara Hills', 'Hyderabad', 'AADHAR004'),
(5, 'Karan Singh', '9876543214', 'karan@gmail.com', 'Sector 17', 'Chandigarh', 'AADHAR005');

INSERT INTO ROOM_TYPE VALUES
(1, 'Standard', 2500, 2),
(2, 'Deluxe', 4000, 3),
(3, 'Suite', 7000, 4),
(4, 'Presidential', 12000, 6),
(5, 'Family', 5500, 5);

INSERT INTO ROOM VALUES
(1, 101, 1, 'Available', 1),
(2, 102, 1, 'Occupied', 2),
(3, 201, 2, 'Available', 3),
(4, 301, 3, 'Maintenance', 4),
(5, 302, 3, 'Available', 5);

INSERT INTO DEPARTMENT VALUES
(1, 'Front Office', 'Ground Floor'),
(2, 'Housekeeping', 'First Floor'),
(3, 'Food and Beverage', 'Ground Floor'),
(4, 'Human Resources', 'Second Floor'),
(5, 'Maintenance', 'Basement');

INSERT INTO EMPLOYEE VALUES
(1, 'Raj Kumar', 'General Manager', 90000, '2020-01-10', 1, NULL),
(2, 'Anita Rao', 'Front Office Manager', 60000, '2021-03-15', 1, 1),
(3, 'Vikram Das', 'Housekeeping Manager', 55000, '2021-06-20', 2, 1),
(4, 'Meena Shah', 'HR Manager', 65000, '2022-02-10', 4, 1),
(5, 'Ravi Kumar', 'Service Executive', 35000, '2023-07-05', 3, 3);

INSERT INTO BOOKING VALUES
(1, '2026-08-01', '2026-08-05', '2026-08-07', 8000, 'Checked-Out', 1, 2),
(2, '2026-08-02', '2026-08-08', '2026-08-10', 14000, 'Checked-Out', 2, 3),
(3, '2026-08-05', '2026-08-12', '2026-08-15', 7500, 'Confirmed', 3, 1),
(4, '2026-08-07', '2026-08-18', '2026-08-20', 24000, 'Checked-In', 4, 4),
(5, '2026-08-10', '2026-08-22', '2026-08-25', 16500, 'Confirmed', 5, 5);

INSERT INTO PAYMENT VALUES
(1, 4000, '2026-08-05', 'UPI', 1),
(2, 4000, '2026-08-07', 'Card', 1),
(3, 7000, '2026-08-08', 'Cash', 2),
(4, 7000, '2026-08-10', 'UPI', 2),
(5, 7500, '2026-08-12', 'Card', 3);

INSERT INTO SERVICE VALUES
(1, 'Room Service', 500),
(2, 'Laundry', 300),
(3, 'Spa', 1500),
(4, 'Airport Pickup', 1000),
(5, 'Conference Hall', 5000);

INSERT INTO SERVICE_REQUEST VALUES
(1, 2, '2026-08-06', 1, 1, 5),
(2, 3, '2026-08-06', 1, 2, 5),
(3, 1, '2026-08-09', 2, 3, 5),
(4, 2, '2026-08-13', 3, 4, 5),
(5, 1, '2026-08-19', 4, 5, 5);
