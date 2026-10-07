# FullCaliberApModel + RHA baseline — 설계 제안 (코드 없음, 리뷰용)

상태: **제안**. 이 문서가 승인되기 전에는 solver 코드를 쓰지 않는다.
전제: traversal API(`PenetratorModel.solve(ImpactContext, ArmorLayer): PenetrationResult`)는 동결되어 있고, 이 모델은 그 안에서만 동작한다.

## 0. 범위와 정직한 한계

**첫 구현은 가장 좁게:** 전구경(full-caliber) **비피모(uncapped) AP** 탄 × **균질압연장갑(RHA)** × 수직/경사 충돌.

범위 밖(후속 단계): APC, APCBC, APCR/APDS, APFSDS(long rod), HEAT/HESH, 표면경화 장갑, 복합장갑, 다층 상호작용.

이 문서에서 **이번 세션에 실제로 확인한 공개 자료**와 **기억에 의존하는 항목**을 구분해 표시한다.
- (확인) de Marre 원형은 에너지형 `E = B · D^1.5 · t^1.4` (지수 합 2.9)이고, 1890년대 니켈강 장갑 자료에 맞춘 경험식이다.
- (확인) 1937년 미 해군 *Naval Ordnance* 12장(전사본)은 de Marre 식이 **타격속도 1,400–2,000 ft/s(약 427–610 m/s), 탄경/판두께 비 0.7–1.2 범위에서 가장 정확하고, 그 밖에서는 "erratic or misleading"** 이라고 쓴다. 표면경화 장갑에는 계수 K를 도입해 쓴다.
- (확인) Recht–Ipson(1963) / Lambert–Jonas(1976) 잔류속도 식의 **함수 형태** `v_r = a (v_i^p − v_bl^p)^(1/p)`, 그리고 Recht–Ipson이 에너지·운동량 보존에 기반한다는 서술.
- (확인) MIL-STD-662F의 V50 정의: 완전관통과 부분관통이 같은 확률인 타격속도.
- (미확인) 위 식들의 **수치 계수·지수·적용 데이터셋**. 이 문서에는 어떤 계수 값도 쓰지 않는다. DTIC 문서(AD0310022)는 403으로 읽지 못했으므로 인용하지 않는다.
- (기억, 구현 전 원문 확인 필수) 아래 8절의 참고문헌 서지사항.

## 1. 정확한 식

### 1.1 탄도한계속도 (ballistic limit) — 경험식

de Marre 계열을 **참조값 정규화 형태**로 쓴다. 단위가 붙은 상수 `B`를 피하기 위해서다(`B`의 단위는 J·m^-2.9로 오해하기 쉽다).

```
v_bl(θ=0) = v_ref · (d / d_ref)^a · (T / T_ref)^b · (m / m_ref)^(−c)
```

de Marre 원형 `E = B D^1.5 t^1.4`, 즉 `½ m v² = B D^1.5 t^1.4` 를 속도로 풀면 `v = √(2B) · D^0.75 · t^0.7 / m^0.5` 이므로, 역사적 가설 H0은 **a = 0.75, b = 0.7, c = 0.5** 이다.

**제안:** H0을 *가정*하지 않고 *검증 대상*으로 둔다. `v_ref, a, b, c`는 **보정 데이터에 맞춰 적합**하고, 적합 결과가 H0과 다르면 그 값을 쓴다. 지수와 계수는 "어떤 시험 데이터에 맞춘 것인가"가 모델의 일부이므로 **출처 메타데이터와 함께** 저장한다(아래 7절).

### 1.2 경사 충돌 (obliquity)

```
v_bl(θ) = v_bl(0) · M(θ)        θ: 표면 법선으로부터의 각도 [rad], 0 = 수직
```

`M(θ)`는 *각도 배수*이며 두 가지 중 데이터에 맞는 쪽을 쓴다.
- (A) 표(권장): `ResistancePreset.curves["obliquity_multiplier"]` = `[θ, M]` 점 목록(구간 선형 보간). 이미 있는 `curves` 스키마를 그대로 쓴다.
- (B) 거듭제곱: `M(θ) = sec(θ)^n`, `n`은 적합 파라미터.

de Marre 식에서 두께만 시선두께로 바꾸면 `T → T/cosθ` 이므로 `M = sec^b`(= sec^0.7 for H0)가 된다. 이것은 *하나의 가설*이지 보정된 값이 아니다. 1937년 교범은 "velocity ... varies about as the fourth power of the secant"라고 쓰는데(전사본 문장, 문맥상 모호), **이 값을 그대로 쓰지 않고** 보정 단계에서 데이터로 판단한다.

`M(θ)`의 **유효 구간은 0°–약 60°** 로 제한한다. 그 이상(교범도 60–90°를 별개 거동으로 구분)은 관통식으로 외삽하지 않고 도탄 단계(1.4)가 먼저 판정한다.

### 1.3 잔류속도 (v_i > v_bl 일 때)

Recht–Ipson / Lambert–Jonas 형태:

```
v_r = a_r · ( v_i^p − v_bl^p )^(1/p)        v_i ≤ v_bl 이면 관통 없음
```

- `p = 2`: 에너지 보존 기반. 기본값으로 제안(적합 파라미터로 열어 둠).
- `a_r`: 운동량 보존 기반 계수. Recht–Ipson 유도에서는 **탄 질량과 떨어져 나가는 플러그(plug) 질량의 비**로 나온다. 제안: `a_r = m / (m + m_plug)`, `m_plug = ρ_plate · (π/4) · d² · T_los`, `T_los = T / cosθ`. 즉 **재질 밀도·기하에서 계산**하고 임의 적합하지 않는다. (원논문의 유도와 일치하는지 구현 전에 대조한다. 일치하지 않으면 `a_r`을 데이터 파라미터로 바꾼다.)
- 이 선택은 에너지 불변식과 양립한다: `½(m+m_plug)·v_r² = ½ m a_r (v_i² − v_bl²) ≤ ½ m v_i²` (a_r ≤ 1, p = 2).

### 1.4 도탄 → 파쇄 → 관통의 순차 판정

한 층에서 **세 단계를 순서대로** 거친다. 각 단계는 자기 파라미터를 가지며 서로의 식을 오염시키지 않는다.

1. **도탄(RICOCHET)**: `θ ≥ θ_crit(v_i, T/d, …)` 이면 도탄. `θ_crit`은 *속도에 따라 증가하는 곡선*이다(공개 문헌: 속도가 오르면 임계각이 커지고 판 두께·탄두 형상에 의존한다). `curves["ricochet_critical_angle"]` = `[v_i, θ_crit]`, 필요하면 `T/d`별 곡선 집합. 도탄 시 잔류 상태는 `ProjectileState`의 속도 벡터를 반사 + 속도 손실 계수(데이터)로 만든다.
2. **파쇄(SHATTERED)**: 충돌 에너지/속도, 경사, 탄체·판 경도 비가 `v_shatter` 곡선을 넘으면 파쇄. 첫 구현에서는 **곡선이 없으면 파쇄를 판정하지 않는다**(발명하지 않는다).
3. **관통 판정**: `v_i` 와 `v_bl(θ)` 비교 → `PERFORATED`(+ 잔류 `v_r`) 또는 정지.

`PARTIAL`(크레이터/팽창)은 **첫 구현에서 생성하지 않는다**(`STOPPED`만). 근거 데이터가 확보되면 `v_i ≥ f · v_bl` 같은 띠를 별도 파라미터로 추가한다.

## 2. 변수와 SI 단위

| 기호 | 의미 | 단위 | 출처 |
|---|---|---|---|
| `d` | 탄체 직경(전구경 AP는 포 구경과 같음) | m | `ProjectileDefinition.projectileDiameterM` |
| `m` | 탄 전체 질량(역사적 보정은 포탄 전체 중량 기준) | kg | `ProjectileState.massRemainingKg` |
| `T` | 판의 **법선 두께** | m | `ArmorLayer.thicknessM` |
| `θ` | 법선으로부터의 입사각 | rad | `ImpactContext.angleFromNormalRad` (파생) |
| `v_i` | 충돌 속도 | m/s | `ProjectileState.speedMps` |
| `v_bl`, `v_r` | 탄도한계/잔류 속도 | m/s | 계산 |
| `ρ_plate` | 판 밀도 | kg/m³ | `ArmorMaterial.densityKgM3` |
| `v_ref, d_ref, T_ref, m_ref` | 보정 기준값 | m/s, m, m, kg | 프리셋(데이터) |
| `a, b, c, n, p` | 지수 | 무차원 | 프리셋(데이터) |

이 solver는 `ProjectileDefinition.penetrator`를 쓰지 않는다(전구경 AP는 탄 전체가 관통체이므로 `d`, `m`을 탄에서 읽는다). 이 모델이 `penetrator == null` 정의만 받도록 하고, 아니면 거부한다(오용 방지).

## 3. 보정 계수의 의미 (`ResistancePreset` 스키마)

`ResistancePreset(solver = dncity:full_caliber_ap)`의 `parameters`/`curves`:

- `parameters`: `v_ref_mps, d_ref_m, T_ref_m, m_ref_kg, exp_d, exp_T, exp_m, residual_p`, (경사 (B)안) `obliquity_exponent`.
- `curves`: `obliquity_multiplier`(A안), `ricochet_critical_angle`, 선택 `ricochet_speed_retention`.
- **유효 구간**: `valid_v_min/max_mps, valid_d_over_T_min/max, valid_theta_max_rad` 를 파라미터로 둔다.
- **출처 메타데이터**(`provenance`: 자료명, 시험 표준, 판 경도, 적합 방법과 오차)를 프리셋에 선택 필드로 추가하는 것을 제안한다. 데이터 모델 변경이지만 traversal API는 건드리지 않는다.

**RHA 기준**: 프리셋 ID `dncity:rha_ap`. 어떤 규격/경도의 RHA를 "기준"으로 삼을지(예: MIL 규격의 경도 범위)는 **보정 데이터가 따르는 규격으로 정한다**(미정, 결정 사항 1).

**이 저장소에는 보정 전 수치를 넣지 않는다.** 단위 테스트의 수치는 `src/test` 픽스처에만 둔다.

## 4. 탄도한계 정의

`v_bl` := **V50 of complete penetration** (MIL-STD-662F 정의: 완전관통과 부분관통이 같은 확률인 속도), 증인판 없이 **판 자체를 완전히 통과**하는 한계.

주의: 자료마다 한계 정의가 다르다(미 해군 한계, 미 육군 한계, 방호 한계 PBL). 보정 데이터를 **하나의 정의로 환산**하고, 환산하지 못하는 자료는 보정에 쓰지 않는다.

결정론: 기본은 `v_i > v_bl` 이면 관통(결정적). 선택적으로 한계속도 산포 `σ_v`를 데이터로 두고 `ImpactContext.seed`(도메인 `PENETRATION`)로 추첨한다. 첫 구현은 `σ_v = 0`.

## 5. 잔류 상태 / 에너지 / 위치

- `PERFORATED`: 잔류 속도 `v_r` (방향은 입사 방향 유지), **질량 불변**, `deformation`·`integrity`는 **첫 구현에서 갱신하지 않는다**(마모 모델은 근거 자료 없이 발명하지 않는다). 알려진 한계로 문서화.
- `perforationPoint = 충돌점 + 방향 · T_los` (traverser가 canonical 위치를 소유하므로 solver는 점만 반환).
- 흡수 에너지 `deposited = ½ m v_i² − ½ m v_r² − E_plug`, 플러그는 `SpallSource`(질량 `m_plug`, 에너지 `½ m_plug v_r²`, 축 = 진행 방향)로 보고. 플러그 속도 = `v_r` 라는 Recht–Ipson의 공동속도 가정은 *가정*으로 표시.
- 에너지 불변식은 traversal이 검사한다(생성 금지). 이 solver는 추가로 `deposited ≥ 0` 을 스스로 보장한다.
- 다층: 각 층에 독립 적용한다. 접촉 적층판이 이론상 같은 두께의 일체판과 다르다(de Marre 두께 지수 < 1)는 점은 **알려진 부정확성**으로 기록.

## 6. AP 적용 범위와 외삽 정책

- 대상: 전구경, 비피모, 솔리드 또는 소량 충전 AP. `Geometry ∈ {OGIVE, POINTED, FLAT_NOSE, BLUNT}`.
- 거부: `LONG_ROD`, `SHAPED_CHARGE_CONE`, `penetrator != null`.
- 보정 유효 구간 밖: 식을 **조용히 외삽하지 않는다**. solver 내부 진단(`regime`: IN_RANGE / EXTRAPOLATED / OUT_OF_MODEL)을 두고 테스트와 로그에서 확인한다. `OUT_OF_MODEL`(예: 탄경/두께가 구간을 크게 벗어난 과대/과소 정합)은 결정 사항 3(고정 클램프 vs 외삽+표시)에서 정한다. traversal API는 바꾸지 않고 solver 내부 `solveDetailed()` 를 `solve()`가 감싼다.

## 7. APC / APCBC는 어디에 들어가는가 (이번 범위 밖, 설계만)

세 계열을 **하나의 "DeMarre 탄"으로 합치지 않는다.**

- **AP (이번)**: 위 1절 전부.
- **APC (피모 AP)**: 탄두 피모(cap)는 *관통식 계수*가 아니라 **1.4의 도탄·파쇄 단계**를 바꾼다(파쇄 임계 상승, 임계 도탄각 변화). 표면경화 장갑에서 효과가 크고 균질 RHA에서는 작다. 모델링은 `cap_integrity`(0/1)를 가진 상태 + 1.4 곡선의 cap 변형으로 한다. `ProjectileState`에 필드가 필요하며 이는 별도 승인 후 추가한다.
- **APCBC (피모 + 탄도 캡)**: 탄도 캡(windscreen)은 공기역학용이고 장갑 충돌 시 파괴된다. 터미널 solver는 이를 **별도 효과로 모델링하지 않고**, 질량 `m`에 포함된 것으로 취급한다(역사적 보정이 포탄 전체 중량 기준이므로). 피모 효과는 APC와 같다.

## 8. 공개 보정 자료 (구현 전에 원문으로 확인해야 하는 목록)

이번 세션에서 **본문을 확인한 것**: (1) *Naval Ordnance* (1937) 12장 전사본, (2) de Marre 식 소개 및 Recht–Ipson/Lambert–Jonas 형태를 다루는 검색 결과, (3) MIL-STD-662F V50 정의 요약.

**후보(서지는 기억에 의존, 확인 필요)**:
- Recht & Ipson (1963), *Ballistic perforation dynamics*, J. Applied Mechanics.
- Lambert & Jonas (1976), BRL Report 1852 (DTIC에서 입수 가능 여부 확인).
- MIL-STD-662F (V50 시험 방법) / MIL-DTL-12560 (RHA 규격: 경도 범위).
- Bird & Livingston, *WWII Ballistics: Armor and Gunnery* (de Marre 식 소개 서적으로 인용됨).
- US Navy *Naval Ordnance and Gunnery* (NAVPERS 10797) 계열의 시험 한계속도 표.
- Rosenberg & Dekel, *Terminal Ballistics*; Backman & Goldsmith (1978) 관통 역학 리뷰.

**보정 절차:** (a) 각 점을 `(d, m, T, 경도, θ, v_bl, 시험 정의, 출처)` 로 기록, (b) 한계 정의를 V50 완전관통으로 환산, (c) `ln v_bl = ln v_ref + a ln(d/d_ref) + b ln(T/T_ref) − c ln(m/m_ref)` 로 선형 최소제곱 적합, (d) 홀드아웃 검증과 잔차 보고, (e) 결과와 데이터 출처를 프리셋 `provenance`에 기록. 보정 점들은 **공개 출처가 확인된 것만** 테스트 고정값으로 `src/test`에 둔다.

## 9. 경험식과 보존법칙의 구분

| 항목 | 성격 |
|---|---|
| 에너지 비생성, 질량·운동량 보존, 단위(SI), 시선두께 기하 `T/cosθ` | **법칙/기하** (traversal이 강제하거나 정의상 참) |
| 플러그 질량 `ρ·A·T_los`, `a_r = m/(m+m_plug)`, `p = 2` | 보존법칙 기반이나 **Recht–Ipson의 모형 가정(공동 속도 등)** 에 의존 |
| `v_bl` 거듭제곱식의 계수·지수 | **경험적** (de Marre 계열, 보정 데이터에 종속) |
| 경사 배수 `M(θ)` | **경험적** |
| 임계 도탄각 `θ_crit(v, T/d)` | **경험적**(속도·두께·탄두 형상 의존) |
| 파쇄 임계 | **경험적**, 데이터 없으면 판정하지 않음 |
| 마모(`deformation`), 부분관통 띠, 한계속도 산포 | **미모델**(첫 구현 제외) |

## 10. 테스트 계획 (승인 후)

- **속성**: `v_bl`은 `T`, `d`에 단조 증가, `m`에 단조 감소, `θ`에 단조 증가. `v_i ≤ v_bl` 이면 잔류 없음. `v_i → v_bl⁺` 에서 `v_r → 0`. 임의 입력(퍼징)에서 에너지 불변식이 깨지지 않음.
- **도탄 우선**: 임계각 이상에서는 `v_i`가 아무리 커도 관통 판정으로 가지 않음.
- **결정론**: 같은 `ImpactContext`(시드 포함)는 같은 결과.
- **거부**: `LONG_ROD`, `penetrator != null`, 알 수 없는 프리셋.
- **회귀**: 출처가 확인된 공개 데이터 점만 사용, 허용 오차를 보정 오차에서 도출.
- **traversal 통합**: 기존 합성 모델 테스트를 그대로 통과하고, 실제 solver로 2층(에어갭 포함) 시나리오 1개.

## 11. 결정이 필요한 사항

1. **참조값 정규화 + 적합 지수** 방식(1.1)을 승인하는가, 아니면 H0 지수(0.75/0.7/0.5)를 고정하고 `v_ref`(= K)만 적합하는가?
2. **`PARTIAL` 미생성 + 마모 미갱신**으로 첫 구현을 시작해도 되는가?
3. 유효 구간 밖은 **클램프 vs 외삽+진단 표시** 중 무엇인가?
4. 보정 기준은 **실제 세계 데이터(물리 기준)** 인가, 게임 밸런스용으로 이후 스케일하는가? (이 문서는 전자를 가정)
5. "RHA 기준"으로 삼을 **규격과 경도 범위**.
6. `ResistancePreset`에 선택적 `provenance` 필드를 추가해도 되는가?
