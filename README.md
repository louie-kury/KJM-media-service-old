# KJM-media-service

공통 미디어 서비스의 초기 프로젝트입니다. 파일 검사와 S3 호환 저장·조회, 업로드 작업과 메타데이터의 내부 기록 기능을 제공합니다.

## 준비와 실행

- JDK 17이 필요합니다. Gradle Wrapper가 빌드 도구를 준비하며, 처음 실행할 때 필요한 파일을 내려받을 네트워크가 필요할 수 있습니다.
- MP4 검사를 위해 FFmpeg의 `ffprobe`가 필요합니다. 실행 파일을 PATH에 추가하거나 `MEDIA_FFPROBE_PATH` 환경 변수에 실행 파일의 전체 경로를 지정하세요. 실행 실패나 10초 시간 초과 시 검사를 거절합니다.
- Windows PowerShell에서 아래 명령을 실행합니다. macOS·Linux에서는 `./gradlew`를 사용합니다.

```powershell
.\gradlew.bat test
.\gradlew.bat bootRun
```

실제 `ffprobe`를 사용하는 영상 연동 테스트는 `MEDIA_FFPROBE_PATH`가 설정된 경우 실행됩니다. 설정하지 않으면 해당 테스트는 건너뜁니다. 영상 기능을 검증할 때는 이 변수를 지정하고 테스트 결과에서 건너뛴 항목이 없는지 확인하세요.

S3 연결 테스트는 격리된 로컬 S3Mock과 `MEDIA_TEST_S3_ENDPOINT`가 필요합니다. 예를 들어 S3Mock 5.2.3을 로컬 `127.0.0.1:9090`에서 실행하고 `codex-media-test` 버킷을 준비한 뒤 다음과 같이 실행합니다.

```powershell
$env:MEDIA_FFPROBE_PATH = 'C:\path\to\ffprobe.exe'
$env:MEDIA_TEST_S3_ENDPOINT = 'http://127.0.0.1:9090'
.\gradlew.bat test
```

이 테스트는 명시된 로컬 HTTP 주소만 허용하며 고유 키와 테스트용 자격값을 사용합니다. `MEDIA_TEST_S3_ENDPOINT`를 설정하지 않으면 S3 연동 테스트는 건너뜁니다. S3Mock에서 통과한 결과는 실제 AWS S3의 설정·권한·동작까지 검증했다는 뜻은 아닙니다.

로컬 S3Mock 테스트는 객체 저장 후 응답을 끊어 결과가 불명확한 경우 재시도하지 않는지, 별도 조회로 객체가 실제 저장됐는지 확인합니다. 로컬 소켓 테스트는 연결 실패, 시간 초과, 전송 및 읽기 중단도 확인합니다. 2026-11-25 검증에서는 전체 76개 중 70개가 통과하고 MySQL·ffprobe 미설정으로 6개를 건너뛰었습니다. S3Mock 테스트는 실행됐으며 실제 AWS는 검증하지 않았습니다.

DB 연결을 사용하는 실행에는 MySQL 데이터 소스 설정이 필요하며 Flyway가 `src/main/resources/db/migration`의 마이그레이션을 관리합니다. `bootRun`은 외부 파일 업로드 API를 제공하지 않습니다. HTTP 엔드포인트는 없습니다.

MySQL 통합 테스트는 **전용 로컬 테스트 DB**를 준비한 뒤 실행합니다. URL은 `localhost` 또는 `127.0.0.1`과 `media_test_`로 시작하는 DB 이름만 허용합니다. 처음에는 빈 DB를 사용하세요. 테스트가 스키마와 테스트 행을 남기므로 운영 DB나 다른 용도의 DB를 사용하지 마세요.

```powershell
$env:MEDIA_TEST_MYSQL_URL = 'jdbc:mysql://127.0.0.1:3306/media_test_example'
$env:MEDIA_TEST_MYSQL_USER = '<전용 테스트 사용자>'
$env:MEDIA_TEST_MYSQL_PASSWORD = '<전용 테스트 비밀번호>'
.\gradlew.bat test --tests '*MySqlUploadRecordIntegrationTest' --rerun-tasks
```

`MEDIA_TEST_MYSQL_URL`이 없으면 이 테스트는 건너뜁니다. 비밀번호는 실제 환경에서 설정하고 저장소에 기록하지 마세요. MySQL 26.10의 새 빈 테스트 DB에 Flyway V1이 적용됐고, 이후 제약·롤백·동시 완료와 완료 트랜잭션 커밋 전후 예외 주입을 포함한 통합 테스트 2개가 건너뜀 없이 통과했습니다. 실제 네트워크 응답 유실은 재현하지 않았습니다. Flyway는 검증된 MySQL 지원 버전이 9.4까지라는 경고를 출력했습니다. 사용자가 DB 서버 재시작 전후 `media`·`upload_job`·`temporary_attachment`의 행 수가 각각 5·8·5로 같음을 확인했습니다. 개별 행 내용은 비교하지 않았습니다.

미완료 업로드 조회 테스트는 같은 MySQL 설정을 사용하되 테스트마다 `media_test_pending_`과 임의 ID로 새 스키마를 만들고 종료 시 삭제합니다. 전용 테스트 계정에 해당 이름의 스키마 생성·삭제와 마이그레이션 권한이 필요합니다. 기존 스키마를 지우지 않으며, 프로세스가 강제 종료되면 임시 스키마가 남을 수 있습니다.

```powershell
.\gradlew.bat test --tests '*MySqlPendingUploadQueryIntegrationTest' --rerun-tasks
```

업로드 복구 정보의 마이그레이션·기록 검증도 동일한 MySQL 설정을 사용합니다. 테스트마다 `media_test_recovery_`로 시작하는 임시 스키마를 만들고 종료 시 삭제하므로 전용 계정에 스키마 생성·삭제 권한이 필요합니다. 강제 종료 시 임시 스키마가 남을 수 있습니다.

```powershell
.\gradlew.bat test --tests '*MySqlRecoveryMetadataIntegrationTest' --rerun-tasks
```

내부 업로드 흐름을 확인하려면 위 MySQL 설정과 앞서 설명한 로컬 S3Mock·`codex-media-test` 버킷을 함께 준비하고 실행합니다. DB 테스트 행은 남습니다.

```powershell
$env:MEDIA_TEST_S3_ENDPOINT = 'http://127.0.0.1:9090'
.\gradlew.bat test --tests '*MediaUploadInternalIntegrationTest' --rerun-tasks
```

MySQL 26.10과 로컬 S3Mock을 함께 사용한 내부 호출 테스트 1개가 건너뜀 없이 통과했습니다. 정상 이미지의 저장·기록과 잘못된 파일의 거절을 확인한 결과이며 실제 AWS S3나 공개 API의 검증을 뜻하지 않습니다. 두 환경 변수가 모두 설정되지 않으면 이 테스트는 건너뜁니다.

같은 격리 환경의 독립 QA 테스트 1개도 통과했습니다. 정상 업로드, 잘못된 파일과 중복 작업 거절, 첨부 기록 실패 시 롤백을 확인했습니다. 실제 저장 응답 유실은 재현하지 않았습니다.

## 현재 파일 검사 범위

- 회원·반려동물 프로필, 게시글, 건강 기록, 상담의 이미지에 JPEG·PNG·WebP를 허용합니다. 게시글에는 MP4도 허용합니다.
- 이미지 한 파일은 최대 10,000,000바이트, MP4는 최대 100,000,000바이트입니다. 실제 읽은 바이트를 기준으로 검사합니다.
- 파일 이름의 확장자, 전달받은 MIME 형식, 파일 앞부분의 식별 정보를 대조합니다. JPG와 JPEG 확장자는 모두 JPEG로 취급합니다.
- 사용할 수 없는 유형, 빈 파일, 유형 불일치, 크기 초과, 입력 읽기와 임시 출력 쓰기 실패를 구분합니다.

검사 기능은 `MediaValidationService`를 호출해 사용하며, 호출자가 입력과 임시 출력 스트림의 수명주기를 관리합니다. **검사가 실패하면 임시 출력에 일부 데이터가 남을 수 있으므로 호출자가 폐기해야 합니다.** 성공 시 검사한 내용은 제공한 출력에 기록되고 확인된 유형과 크기가 반환됩니다.

## 내부 미디어 정책 조회

`MediaPolicyService#getPolicy(MediaUsage)`는 사용처, 현재 첨부 최대 개수와 MIME별 파일당 최대 크기(바이트)를 반환합니다. 내부 Java 호출이며 HTTP API는 제공하지 않습니다.

```java
var policy = new MediaPolicyService().getPolicy(MediaUsage.POST_ATTACHMENT);
// policy.usage(): POST_ATTACHMENT
// policy.maxAttachments(): 5
// policy.maxBytesByMimeType(): 이미지 각 10000000, video/mp4 100000000
```

반환된 MIME별 크기 맵은 수정할 수 없습니다. 사용처가 `null`이면 `NullPointerException`이 발생합니다. 허용 형식과 크기는 기존 `MediaUsage`·`MediaType`의 검사 기준을 사용하며, 건강 기록·상담은 각 3개, 회원·반려동물 프로필은 현재 연결 사진 각 1개입니다. 개수는 조회 정보이며 이 기능이 실제 첨부 개수를 제한하지는 않습니다. 프로필 사진 이력의 보관·조회·복원은 아직 제공하지 않습니다.

## 내부 저장·조회

`MediaStorageService`는 호출자가 준비한 객체 키와 입력 스트림을 받습니다. 파일 검사에 성공하면 검사된 바이트의 SHA-256 체크섬을 계산해 `MediaObjectStorage` 포트에 전달하고, S3 어댑터는 조건부 저장으로 기존 객체를 덮어쓰지 않도록 요청합니다. SDK 자동 재시도는 한 번의 요청으로 제한하며, 저장 응답의 체크섬을 확인할 수 없으면 성공을 단정하지 않습니다. S3 클라이언트 빌더의 리전·자격 증명과 버킷은 호출자가 구성하며, 빌더에 지정한 시간 초과 설정은 유지됩니다. 테스트용 엔드포인트나 자격값을 운영 환경에 사용하지 않습니다.

내부 `observe`는 저장 객체의 크기·Content-Type·반환된 SHA-256 체크섬을 확인합니다. 체크섬이 제공되지 않으면 null이며, 복합 체크섬을 파일 전체 체크섬과 같다고 판단하지 않습니다. HEAD의 404는 원인이 모호하므로 GET으로 다시 확인하고 명시적인 NoSuchKey만 객체 없음으로 분류합니다. 두 조회 사이 객체가 생성돼 GET이 성공하면 정보를 확인한 후 스트림을 중단합니다. 권한이나 통신 오류는 확인 실패로 처리합니다. 이 결과는 한 시점의 관찰이므로 이후 완료될 수 있는 쓰기, 안전한 복구 또는 삭제를 보장하지 않습니다.

입력 스트림과 S3 어댑터는 호출자가 닫습니다. 저장 서비스는 내부 임시 파일을 정리하고, 조회 결과인 `StoredObject`도 사용한 쪽에서 닫아야 합니다. 저장 결과를 확인할 수 없는 경우 `OUTCOME_UNKNOWN`, 저장은 완료됐으나 임시 파일 정리에 실패한 경우 `STORED_CLEANUP_FAILED`, 없는 객체는 `NOT_FOUND`로 구분합니다. 이미 발생한 오류와 정리 오류가 겹치면 최초 오류에 정리 오류가 함께 보존됩니다.

## 내부 업로드 기록

`MediaUploadService`는 내부 호출을 위한 기능입니다. 새 객체 키와 업로드 작업을 DB에 기록한 후 파일을 검사·저장합니다. 저장이 확인되면 검증된 유형·실제 크기를 사용해 `media`와 `temporary_attachment`를 기록하고 작업을 완료 상태로 바꿉니다. 세 변경은 하나의 DB 트랜잭션에서 처리합니다. 저장 호출은 DB 트랜잭션 밖에서 수행합니다.

파일 검사가 끝나면 S3 저장 요청 전에 파일명, 검증된 유형, 실제 크기와 Base64 인코딩한 SHA-256을 `upload_job`에 기록합니다. 이 기록이 실패하거나 결과를 확인하지 못하면 S3 저장을 시작하지 않고 오류를 전달합니다. 정보는 `PREPARED` 작업에 한 번만 함께 기록하며, 단건 및 미완료 작업 내부 조회에서 확인할 수 있습니다. V2 마이그레이션 이전에 생성된 작업은 해당 정보가 없을 수 있습니다.

이 정보는 향후 복구를 위한 참고 자료입니다. 정보가 있다는 사실만으로 S3 객체의 존재·일치나 쓰기 종료를 보장하지 않으며, 자동 복구·선점·정리는 수행하지 않습니다.

작업 상태는 `PREPARED`, `OUTCOME_UNKNOWN`, `STORED`, `FAILED`, `COMPLETED`로 기록합니다. 저장 결과가 불명확하거나 저장 후 기록이 실패하면 작업 기록이 남을 수 있습니다. `PREPARED` 상태가 남았다고 파일이 저장되지 않았다고 단정할 수 없습니다. 저장 성공 뒤 임시 파일 정리가 실패하면 완료 기록을 시도하고 정리 오류를 호출자에게 전달합니다.

완료 처리에서 예외가 나면 작업을 다시 조회합니다. 상태가 `COMPLETED`이고 기록된 미디어 ID가 이번 처리에서 발급한 ID와 같을 때만 완료를 확인합니다. 그 외에는 원래 예외를 전달하며, 조회 오류도 함께 보존합니다.

이 기능은 자동 복구나 정리 작업을 제공하지 않습니다. 내부 서비스와 S3 클라이언트의 운영용 연결 구성도 아직 제공하지 않으며, 테스트 대역의 결과는 실제 MySQL이나 AWS S3 연동 검증을 뜻하지 않습니다.

## 미완료 업로드 작업 조회

`UploadRecordStore.findPending(cutoff, cursor, limit)`은 기준 시각 이하에 갱신된 `PREPARED`, `OUTCOME_UNKNOWN`, `STORED` 작업을 내부적으로 조회합니다. `cutoff`는 `Instant`, `limit`은 1~100이며 첫 페이지의 `cursor`는 `null`입니다. 결과는 갱신 시각과 작업 ID 오름차순이며, 다음 페이지에는 마지막 결과의 시각과 ID로 커서를 만듭니다.

```java
var page = records.findPending(cutoff, null, 100);
if (!page.isEmpty()) {
    var last = page.get(page.size() - 1);
    var cursor = new PendingUploadCursor(last.updatedAt(), last.job().id());
    var nextPage = records.findPending(cutoff, cursor, 100);
}
```

결과는 조회 시점의 후보이며 조회 중 상태가 바뀔 수 있습니다. 선점·복구·삭제가 가능한지 보장하지 않고, 전체 순회 중 변경된 작업은 다음 순회에서 다시 확인해야 합니다. `PREPARED`나 `OUTCOME_UNKNOWN`은 실제 객체가 없다는 뜻이 아닙니다. 이 조회는 HTTP API나 자동 복구 기능을 제공하지 않습니다.

새로 기록하거나 갱신하는 업로드 작업과 새 미디어·임시 첨부의 시각은 UTC 기준으로 저장합니다. 이전에 저장된 `DATETIME` 값은 당시 DB 세션 시간대를 따르므로 시간대가 불명확할 수 있고 자동 변환되지 않습니다. 기존 데이터를 시간 기준으로 조회하기 전 실제 저장 당시 시간대를 확인해야 합니다.
## 현재 한계

- 파일 앞부분의 식별 정보 검사로 형식을 추정합니다. MP4는 `ffprobe`로 표지 이미지가 아닌 영상 스트림을 확인합니다. 파일 전체의 무결성, 재생 가능 여부나 악성 코드가 없음을 보장하지 않습니다. MP4의 `ftyp` 상자는 앞 4,096바이트 안에 있어야 합니다.
- 로그인·권한 확인, 외부 업로드·조회 API, 사용처 첨부 연결과 개수 제한, 삭제·복구 및 운영 배포는 아직 제공하지 않습니다. 운영 업로드에는 저장 실패와 불명확한 결과의 복구가 필요합니다.
- 기존에 선언된 파일 크기 대신 실제 스트림에서 읽은 크기를 사용합니다. 외부 API의 요청·응답과 파일 저장 동작은 이후 연동에서 정합니다.
