-- -- USER DB -- user
-- create table if not exists "user" (
--     user_id serial primary key,
--     first_name varchar(255) not null,
--     last_name varchar(255) not null,
--     created_at timestamp default current_timestamp
-- );

-- -- USER DB -- system data
-- create table if not exists unit_system (
--     unit_system_id serial primary key,
--     system_name varchar(255) not null unique
-- );

-- create table if not exists energy_system (
--     energy_system_id serial primary key,
--     system_name varchar(255) not null unique
-- );

-- create table if not exists health_goal (
--     health_goal_id serial primary key,
--     goal_name varchar(255) not null unique
-- );

-- -- USER DB -- user data
-- create table if not exists user_email (
--     user_id integer primary key,
--     email varchar(255) not null unique,
--     foreign key (user_id) references "user"(user_id)
-- );

-- create table if not exists user_password (
--     user_id integer primary key,
--     password varchar(255) not null,
--     foreign key (user_id) references "user"(user_id)
-- );

-- create table if not exists user_parameters (
--     user_id integer primary key,
--     weight int not null,
--     height int not null,
--     birth_date date not null,
--     unit_system_id int not null,
--     foreign key (user_id) references "user"(user_id),
--     foreign key (unit_system_id) references unit_system(unit_system_id)
-- );

-- -- USER DB -- user preferences
-- create table if not exists user_unit_system (
--     user_id integer primary key,
--     unit_system_id int not null,
--     foreign key (user_id) references "user"(user_id),
--     foreign key (unit_system_id) references unit_system(unit_system_id)
-- );

-- create table if not exists user_energy_system (
--     user_id integer primary key,
--     energy_system_id int not null,
--     foreign key (user_id) references "user"(user_id),
--     foreign key (energy_system_id) references energy_system(energy_system_id)
-- );

-- create table if not exists user_goals (
--     user_id integer primary key,
--     health_goal_id int not null,
--     daily_steps int not null,
--     water_intake int not null,
--     energy_intake int not null,
--     sleep_hours int not null,
--     foreign key (user_id) references "user"(user_id),
--     foreign key (health_goal_id) references health_goal(health_goal_id)
-- );