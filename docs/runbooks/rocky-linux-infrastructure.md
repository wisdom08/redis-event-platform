# Rocky Linux VM에서 MySQL·Redis 실행

대상은 Mac 안의 Rocky Linux 10.2 aarch64 VM이다. SSH 키 등록과 Docker Engine·Compose 플러그인 설치가 끝난 상태에서 진행한다. 이 절차는 DB·Redis만 실행하며 API 배포는 포함하지 않는다.

## 1. Mac에서 파일 전달

Mac의 프로젝트 루트에서 실행한다. `USER`와 `VM_IP`는 Linux 계정과 VM 주소로 바꾼다. 아직 원격 저장소에 반영하지 않은 파일도 아래 방법으로 전달할 수 있다.

```bash
ssh USER@VM_IP 'mkdir -p ~/redis-event-platform'
scp compose.yml .env.example USER@VM_IP:~/redis-event-platform/
```

## 2. Linux에서 환경 설정

이하 명령은 SSH로 접속한 Linux에서 실행한다.

```bash
cd ~/redis-event-platform
cp -n .env.example .env
chmod 600 .env
vi .env
```

`DB_PASSWORD`와 `MYSQL_ROOT_PASSWORD`를 서로 다른 비밀번호로 변경한다. 예시 값은 로컬 개발 전용이다. 비밀값은 Git에 올리지 않는다. `.env`를 셸에서 source할 수도 있으므로 특수문자가 있으면 값을 작은따옴표로 감싼다. 초기화 후 `.env`의 비밀번호만 바꿔도 기존 DB 계정의 비밀번호가 자동 변경되지는 않는다.

## 3. 실행 및 확인

```bash
sudo systemctl enable --now docker
sudo docker compose config --quiet
sudo docker compose up -d --wait
sudo docker compose ps
sudo docker compose exec redis redis-cli ping
sudo docker compose exec mysql mysql -u event_platform -p event_platform
```

DB 사용자명을 변경했다면 마지막 명령도 맞춘다. MySQL 비밀번호 프롬프트에는 `DB_PASSWORD` 값을 입력한다. 접속 후 확인한다.

```sql
SELECT VERSION(), @@character_set_server, @@global.time_zone;
SELECT 1;
exit
```

Redis는 `PONG`, MySQL은 버전 8.4.13·utf8mb4·+00:00 및 SELECT 1 성공을 확인한다. 컨테이너는 ARM64 이미지를 자동 선택하며 별도 amd64 강제 옵션을 사용하지 않는다.

## 4. 로그와 종료

```bash
sudo docker compose logs --tail=100 mysql redis
sudo docker compose down
```

`down`은 데이터 볼륨을 유지한다. `down -v`는 데이터를 삭제하므로 사용하지 않는다. 재부팅 후에는 unless-stopped 정책으로 복구되며 수동으로 중지한 서비스는 다시 시작해야 한다.

MySQL 3306과 Redis 6379는 VM의 127.0.0.1에만 공개된다. Mac에서 직접 VM_IP:3306에 접근할 수 없는 것이 의도한 설정이며 방화벽 포트를 열 필요가 없다. 필요 시 SSH 터널을 사용한다.

## 시간 오류

패키지 설치에서 `signature is not alive / Not live until`이 나오면 `date -u`, `timedatectl`, `chronyc tracking`으로 시간을 확인한다. NTP active만으로 동기화 성공을 의미하지 않는다. VM 시간 동기화 후 패키지 설치를 재시도하고 GPG 검증을 끄지 않는다.
