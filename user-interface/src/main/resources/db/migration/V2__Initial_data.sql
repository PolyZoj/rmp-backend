-- Заполнение справочников

-- Системы измерений
INSERT INTO unit_system (system_name) VALUES ('Метрическая') ON CONFLICT DO NOTHING;
INSERT INTO unit_system (system_name) VALUES ('Имперская') ON CONFLICT DO NOTHING;

-- Энергетические системы
INSERT INTO energy_system (system_name) VALUES ('Калории') ON CONFLICT DO NOTHING;
INSERT INTO energy_system (system_name) VALUES ('Джоули') ON CONFLICT DO NOTHING;

-- Цели здоровья
INSERT INTO health_goal (goal_name) VALUES ('Снижение веса') ON CONFLICT DO NOTHING;
INSERT INTO health_goal (goal_name) VALUES ('Поддержание веса') ON CONFLICT DO NOTHING;
INSERT INTO health_goal (goal_name) VALUES ('Набор мышечной массы') ON CONFLICT DO NOTHING;
INSERT INTO health_goal (goal_name) VALUES ('Улучшение выносливости') ON CONFLICT DO NOTHING;
INSERT INTO health_goal (goal_name) VALUES ('Улучшение общего здоровья') ON CONFLICT DO NOTHING;