CREATE DATABASE moodify_recommendation_test_000000000000;
CREATE USER 'rec_fixture'@'%' IDENTIFIED BY 'isolated-fixture-only';
GRANT ALL PRIVILEGES ON `moodify\_recommendation\_test\_%`.* TO 'rec_fixture'@'%';
