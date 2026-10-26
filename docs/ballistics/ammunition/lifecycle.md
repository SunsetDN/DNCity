# 탄종 등록·데이터 수명주기 (Phase 0 조사 결과)

> 상태: 조사 및 결정안. 코드 변경 없음. 이 문서의 "검증됨"은 이 저장소 또는 NeoForge jar에서 직접 확인한 것,
> "미검증"은 실제 실행으로 확인해야 하는 것이다.
> War Thunder의 데이터·코드·에셋·수치는 조사하지도 사용하지도 않았다. 참고한 것은 "데이터가 구조를 선언한다"는 일반 개념뿐이며
> 복제한 자료가 없으므로 기록할 출처·라이선스도 없다.

## 1. 조사한 파일

| 대상 | 확인한 것 |
|---|---|
| `gradle.properties` | MC 1.21.1, `neo_version=21.1.248` (Gradle 캐시에는 `neoforge-21.1.216` 클래스 jar만 있음) |
| `Dncity.kt` | `@Mod` object의 `init {}`에서 `ModItems/ModDataComponents.REGISTRY.register(MOD_BUS)`. 리로드 리스너는 `NeoForge.EVENT_BUS`의 `AddReloadListenerEvent`. |
| `item/ModItems.kt`, `ModDataComponents.kt` | `DeferredRegister.createItems`, 정적 등록. 동적 등록 코드 없음. |
| `music/MusicPaths.kt`, `security/PgpPaths.kt` | 이미 `FMLPaths.CONFIGDIR`을 사용 (런타임 파일 I/O 선례). |
| `ballistics/terminal/JsonDataRegistry.kt`, `TerminalBallistics.kt` | 탄도 데이터는 데이터팩 리로드(`data/<ns>/dncity/...`)로 로드. 등록 이후 단계. |
| NeoForge jar | `RegistryManager`, `RegistrySnapshot`, `network/configuration/SyncRegistries`, `ModMismatchDisconnectedScreen` 클래스 존재 확인. |
| `mods/TACZ-1.21.1` | `ModItems.AMMO = ITEMS.register("ammo", AmmoItem::new)`: **TACZ 탄약은 아이템 1개 + `ammoId` 컴포넌트**. |
| `mods/SuperbWarfare` | 탄약은 종류(`Ammo.HEAVY` 등)별 고정 아이템 + 플레이어 데이터 컴포넌트(`ammo_<type>`)의 수량. 구경별 아이템이 아님. |

## 2. 등록 수명주기

```
JVM 시작
 └ FML 모드 로딩: @Mod init{} 실행 (DeferredRegister를 MOD_BUS에 연결)   ← 여기서 파일 I/O 가능 (검증됨: Music/Pgp)
 └ RegisterEvent (레지스트리별, Items 포함)                              ← DeferredRegister가 이때 Item을 생성. 이후 레지스트리 동결
 └ 리소스/데이터팩 로드 (서버 월드 로드 시 AddReloadListenerEvent)       ← ResourceManager 사용 가능, 등록은 이미 끝남
접속
 └ 구성 단계 SyncRegistries: 서버/클라이언트 레지스트리 스냅샷 비교      (클래스 존재 검증됨, 불일치 시 접속 거부 동작은 미검증)
```

결론:

1. **고유 Item ID를 JSON에서 만들려면 `init{}`/`RegisterEvent` 시점에 읽을 수 있는 파일이 필요하다.**
   데이터팩(`data/`)은 그 시점에 없다. 읽을 수 있는 곳은 `FMLPaths.CONFIGDIR`(또는 게임 디렉터리), 또는 모드 jar 자체의 리소스다.
   서버의 `config/`를 클라이언트가 읽을 수는 없으므로, 클라이언트와 서버가 같은 ID 집합을 사전에 갖게 하는 것은 런처(또는 수동 배포)의 책임이다.
2. 접속 시 아이템 레지스트리 불일치는 NeoForge가 거부하는 것으로 알고 있으나 이 조사에서는 실행으로 확인하지 못했다. **Phase 2 Gate에서 반드시 실제 접속으로 검증한다.**
3. 실행 중 새 Item ID 삽입은 불가능한 것으로 가정한다 (레지스트리 동결). 탄도 수치 교체와 아이템 ID 변경을 분리하는 설계 원칙은 유지한다.

## 3. 아이템 정체성 비교

| | 탄종마다 고유 Item ID | 범용 Item + `ammo_id` 컴포넌트 |
|---|---|---|
| 신규 탄종 | 사전 manifest + **재시작** + 런처 동기화 필수 | 데이터 리로드만으로 가능 |
| 클라이언트/서버 동일성 | 레지스트리 ID 집합 일치 필수 (불일치 = 접속 거부 위험) | 컴포넌트 값만 다름, 레지스트리 불변 |
| 레시피/전리품/태그 | 기본 아이템 기능 그대로 | 컴포넌트 조건 필요, 태그는 구분 불가 |
| 모델/텍스처 | 표준 `models/item/<id>.json` | 오버라이드 predicate 또는 커스텀 렌더링 |
| 이름/스택 크기 | `Item.Properties`, 번역키 | 스택에 컴포넌트로 기록 (`CUSTOM_NAME`, `MAX_STACK_SIZE`) |
| rename/삭제 | ID 변경 = 월드 데이터 migration 필요 | 컴포넌트 문자열 migration (아이템은 안전) |
| 삭제된 탄종 | 레지스트리 항목이 사라져 월드 로드 시 아이템 소실/경고 | 무해한 "알 수 없는 탄종" 스택으로 남음 |
| 외부 모드 | 다른 모드가 ID를 직접 참조 가능 | TACZ와 동일한 패턴 (`tacz:ammo` + `ammoId`) |

**권장**: 기본 후보였던 고유 Item은 "재시작+런처 동기화"를 감수할 때만 이점이 있고, 이 저장소의 외부 모드 중 TACZ가 이미 범용 Item+컴포넌트 방식이다.
따라서 **Phase 5의 TACZ 연동에서는 새 아이템을 만들지 말고 `tacz:ammo`의 `ammoId`를 `AmmunitionDefinition`에 매핑**하는 것이 중복 생성을 피하는 가장 안전한 경로다.
고유 Item ID는 DNCity가 직접 소유하는 탄약 중 "레시피/전리품/태그가 ID에 의존하는 것"으로 한정하는 혼합 정책을 제안한다 (결정 필요, 8절).

## 4. 현재 저장소 상태와 이 계획의 차이 (중요)

이 문서 이전에, 이미 승인된 이전 설계에 따라 다음이 **구현되어 있다** (커밋되지 않은 작업 트리):

- `ballistics/terminal/ammo/` (스키마 v1 DTO/파서/검증, `TableLookup`, `AmmoImpactResolver`, `AmmoRegistry`), `AmmoItem`(범용 Item+컴포넌트), `/ammo` 명령, 단위 테스트 20개.

이는 이 계획의 Phase 1, 3, 4 일부를 **Gate 이전에 앞질러 구현한 것**이고, 방향도 다르다:

| 이 계획 | 현재 구현 |
|---|---|
| manifest/revision/해시, 사전 등록 | 없음. 데이터팩 리로드만 사용 |
| `terminal.solver` 미구현은 production 거부 | 로드 허용 + 질의 시 UNRESOLVED (경고 로그) |
| `ModelEvaluation` 공통 결과, `BallisticsResolver`가 solver 선택 | `AmmoEvaluation` + `AmmoImpactResolver` (별도 타입) |
| 발사 시점 definition revision 고정 | 없음 |
| 서버 권위 스냅샷, 접속 시 해시 확인 | 없음 (데이터팩 동기화에 의존) |
| 필드 이름 (`external_ballistics` 블록, `angles_deg`, `external_ammo_ids`) | 다름 (`projectile.drag_coefficient`, `angles`, 없음) |

제안: 현재 구현을 **개발용 기준선**으로 유지하고, 승인 후 Phase 1부터 스키마 이름을 이 계획에 맞춰 이전한다 (되돌릴 필요는 없고 migration 대상).
어느 쪽으로 정리할지는 결정이 필요하다.

## 5. TABLE과 속도/거리의 이중 감속 (5.1 요구사항 답변)

표는 "거리 d에서의 한계"이므로 거리 d에는 이미 d까지의 감속이 포함돼 있다. 현재 구현은 **거리를 입력으로 받거나, 충돌 속도에서 탄 자체의 항력으로 역산해 하나의 값만 사용**하고, 속도와 거리를 독립 입력으로 쓰지 않는다 (감속 중복 없음, `DISTANCE_DERIVED_FROM_SPEED` 진단 표시).
한계: 균일 대기·중력 무시 가정이고 관통 후 속도(이미 한 번 관통한 탄)에는 쓸 수 없다 (`PROJECTILE_NOT_AS_TESTED`로 거부).

## 6. 설계 위험

1. 레지스트리 불일치 거동이 미검증 (2절). 틀리면 manifest 설계 전체가 바뀐다.
2. 런처가 없는 환경(개발 `runClient`/`runClient2`)에서 manifest 동기화를 어떻게 흉내 낼지.
3. 고유 Item 방식은 삭제/rename 시 월드 아이템 소실. 범용 방식은 아이템 모델 구분이 어렵다.
4. TACZ `ammoId` 체계와 DNCity ID 체계의 1:1 매핑 정책이 없다 (Phase 5).
5. `FullCaliberApModel`의 fixture-only 보정(M318A1)은 production에 사용하지 않는다. 현재 `FullCaliberApAmmoSolver`는 프리셋 공급자가 비어 있어 항상 UNRESOLVED이며 이를 유지한다.
6. 앞질러 구현한 코드가 승인 없이 굳어질 위험 (4절).

## 7. 테스트 계획 (Gate별 추가분)

- Phase 0 Gate 검증 항목: ① `init{}`에서 `config/dncity/ammo/manifest.json`을 읽어 `RegisterEvent`에서 Item 2개를 등록하는 최소 실험, ② 서버만 ID를 하나 더 가진 상태로 `runServer`+`runClient` 접속 시 거동 기록, ③ 아이템 하나가 빠진 월드를 열 때의 거동 기록. 이 세 실험 결과로 2절 미검증 항목을 닫는다.
- 이후 단계는 원 계획 10절을 따른다.

## 8. 미해결 질문 (결정 필요)

1. 아이템 정체성: DNCity 소유 탄약에 한해 고유 Item(manifest) + 나머지는 범용 컴포넌트의 혼합 정책을 채택할지.
2. 이미 구현된 `ammo/` 기준선을 유지하고 승인 후 스키마를 이전할지, 이 계획대로 새로 시작할지.
3. 배포: 런처 관리 JSON만 허용할지, 서버 로컬 JSON을 서명 manifest에 편입할지.
4. 리로드: 전투 중 물성 변경을 허용할지 (maintenance window만 허용 권장).
5. production 승인 기준(출처 품질, 불확실성, criterion 정책).
6. TACZ/SW 우선순위: 위 3절의 "기존 탄약은 매핑만" 원칙을 확정할지.
