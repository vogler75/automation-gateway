# Snowflake Instructions   

## Prepare Snowflake

Create a database and a schema for your destination table.
> CREATE OR REPLACE SCHEMA scada;

Create the table for the incoming data:
```
CREATE TABLE IF NOT EXISTS scada.gateway (
  system character varying(1000) NOT NULL,
  address character varying(1000) NOT NULL,
  sourcetime timestamp with time zone NOT NULL,
  servertime timestamp with time zone NOT NULL,
  numericvalue float,
  stringvalue text,
  status character varying(30),
  CONSTRAINT gateway_pk PRIMARY KEY (system, address, sourcetime)
  );
```

Generate a private key: 

> openssl genrsa 2048 | openssl pkcs8 -topk8 -v2 des3 -inform PEM -out snowflake.p8 -nocrypt 

Generate a public key:
> openssl rsa -in snowflake.p8 -pubout -out snowflake.pub

Set the public key to your user:

> ALTER USER xxxxxx SET RSA_PUBLIC_KEY='MIIBIjANBgkqh...'; -- Replace this with your public key from the snowflake.pub file (without -----BEGIN PRIVATE KEY----- and without -----END PRIVATE KEY-----)

Details about creating keys can be found [here](https://docs.snowflake.com/en/user-guide/key-pair-auth)

## Prepare the Gateway

Add a logger section to the gateways config.yml. In that example we take SparkplugB messages, decode it, and write it to Snowflake.

```
Drivers:
  Mqtt:
    - Id: "monster"
      Enabled: true
      LogLevel: INFO
      Host: test.monstermq.com
      Port: 1883
      Format: SparkplugB

Loggers:
  Snowflake:
    - Id: "snowflake"
      Enabled: true
      LogLevel: INFO
      PrivateKeyFile: "security/snowflake.p8"
      Account: xx00000
      Url: https://xx00000.eu-central-1.snowflakecomputing.com:443
      User: xxxxx
      Role: accountadmin
      Scheme: https
      Port: 443
      Database: SCADA
      Schema: SCADA
      Table: GATEWAY
      Logging:
        - Topic: mqtt/monster/path/spBv1.0/vogler/DDATA/govee/#
```

## Start the Gateway

> cd automation-gateway/source/app  
> ../gradlew run