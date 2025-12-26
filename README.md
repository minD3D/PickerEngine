# PickerEngine EC2 Deployment

This guide documents the step-by-step EC2 deployment flow we used.

## 1) Create EC2 instance

- AMI: Ubuntu 22.04 LTS
- Instance type: t3.micro (low cost)
- Public IP: Enable
- Security Group (new):
  - Inbound: SSH (22) from My IP
  - Inbound: TCP 8080 from 0.0.0.0/0 (temporary for testing)

## 2) RDS security group rule

Allow the EC2 instance to access RDS:

- RDS Security Group inbound rule
  - PostgreSQL (5432)
  - Source: EC2 Security Group (recommended)

## 3) Connect to EC2

```bash
chmod 400 pickerengine-key.pem
ssh -i pickerengine-key.pem ubuntu@<EC2_PUBLIC_IP>
```

## 4) Install Java 17

```bash
sudo apt update
sudo apt install -y openjdk-17-jre
```

## 5) Build the app locally

```bash
./gradlew clean bootJar
```

Confirm the JAR exists:

```bash
ls -lh build/libs/
```

## 6) Upload the JAR to EC2

```bash
scp -i pickerengine-key.pem build/libs/*.jar ubuntu@<EC2_PUBLIC_IP>:/home/ubuntu/app.jar
```

Note: If you upload a directory by mistake, run:

```bash
java -jar /home/ubuntu/app.jar/PickerEngine-0.0.1-SNAPSHOT.jar
```

## 7) Set environment variables on EC2

```bash
export RDS_PASSWORD="..."
export IG_SESSIONID="..."
export OPENAI_API_KEY="..."
```

## 8) Run the application

```bash
java -jar /home/ubuntu/app.jar
```

## 9) Test from outside

```bash
curl -s http://<EC2_PUBLIC_IP>:8080/health/db
```

Expected response:

```json
{"ok":true,"result":1}
```

## Troubleshooting

- SSH timeout:
  - Check EC2 inbound rule for SSH (22) and public IP.
- DB connect timeout:
  - RDS must be publicly accessible (for local tests).
  - Ensure RDS Security Group allows 5432 from EC2 SG.
- DB error "database does not exist":
  - Create the database in RDS (for example, "userdata").
