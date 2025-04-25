FROM postgres:alpine

RUN apt-get update && apt-get install -y iputils-ping

COPY conf/postgresql.conf /etc/postgresql/postgresql.conf
COPY conf/pg_hba.conf    /etc/postgresql/pg_hba.conf

COPY init/        /home/init/
COPY scripts/     /home/scripts/

RUN chmod +x /home/init/*.sh \
    && chmod +x /home/scripts/*.sh
