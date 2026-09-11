import { Link } from 'react-router-dom';
import { Icon, type IconName } from '../components/Icon';
import { PageHeader, StatusBadge } from '../components/StatusUi';
import { sampleRateLabels, SAMPLE_RATE_OPTIONS } from '../features/measurement/sampleRates';
import {
  measurementStatusLabels,
  MOVEMENT_DISCLAIMER,
  MOVEMENT_FOOT_UNAVAILABLE,
  MOVEMENT_UNAVAILABLE,
  movementTerms,
  observationLevelLabels,
  observationLevelOrder,
  observationPatternCodes,
  patternCodeLabel,
  qualityFlagLabel,
  qualityLabels,
  receiverStateLabels,
  referenceMethodLabels,
  resultTerms,
} from '../utils/labels';
import type {
  MeasurementStatus,
  ObservationPatternCode,
  QualityLevel,
  ReceiverUploadState,
} from '../api/types';

// 계약 1.1 / rule-v1.2.0 데이터 품질 플래그 코드. 문구는 utils/labels의 qualityFlagLabel만 사용한다.
const qualityFlagCodes: readonly string[] = [
  'LEFT_DEVICE_DISCONNECTED',
  'RIGHT_DEVICE_DISCONNECTED',
  'LEFT_DATA_MISSING',
  'RIGHT_DATA_MISSING',
  'LEFT_DATA_INCOMPLETE',
  'RIGHT_DATA_INCOMPLETE',
  'INSUFFICIENT_DATA',
  'LOW_DATA_QUALITY',
  'SEQUENCE_GAP',
  'OUT_OF_ORDER',
  'SEQUENCE_WRAP_SUSPECTED',
  'DEVICE_TIME_JUMP',
  'SAMPLE_RATE_MISMATCH',
  'SENSOR_STUCK',
  'SENSOR_SATURATION',
  'SENSOR_STUCK_OR_SATURATED',
  'RECEIVER_UPLOAD_INCOMPLETE',
  'FSR_ERROR_REPORTED',
  'IMU_ERROR_REPORTED',
  'BATTERY_LOW_REPORTED',
  'FILTERED_DATA_MODE',
];

const patternIcons: Record<ObservationPatternCode, IconName> = {
  MEDIAL_LOAD_TENDENCY: 'medial',
  LATERAL_LOAD_TENDENCY: 'lateral',
  LEFT_RIGHT_ASYMMETRY: 'asymmetry',
  LOW_HALLUX_SIGNAL: 'hallux',
  FOREFOOT_LOAD_TENDENCY: 'forefoot',
  REARFOOT_LOAD_TENDENCY: 'rearfoot',
};

// 패턴 설명은 어느 부위의 신호가 상대적으로 어떻게 관찰되었는지만 말하고 원인이나 질환은 말하지 않는다.
const patternDescriptions: Record<ObservationPatternCode, string> = {
  MEDIAL_LOAD_TENDENCY:
    '발 안쪽(내측) 센서 신호가 바깥쪽보다 큰 걸음이 반복해서 관찰된 경우입니다.',
  LATERAL_LOAD_TENDENCY:
    '발 바깥쪽(외측) 센서 신호가 안쪽보다 큰 걸음이 반복해서 관찰된 경우입니다.',
  LEFT_RIGHT_ASYMMETRY: '왼발과 오른발의 접촉 시간 차이가 큰 걸음 쌍이 관찰된 경우입니다.',
  LOW_HALLUX_SIGNAL: '엄지발가락 쪽 센서 신호가 다른 부위보다 낮게 관찰된 경우입니다.',
  FOREFOOT_LOAD_TENDENCY: '앞쪽(전족부) 센서 신호가 뒤쪽보다 큰 걸음이 반복해서 관찰된 경우입니다.',
  REARFOOT_LOAD_TENDENCY: '뒤쪽(후족부) 센서 신호가 앞쪽보다 큰 걸음이 반복해서 관찰된 경우입니다.',
};

// 계약 1.3.0 / rule-v1.4.0 정강이 IMU 용어(DEC-036). 이름은 movementTerms만 쓰고 계약(openapi MovementFootSummary)의
// 정의를 요약해 값의 의미만 설명한다. 참고 범위나 판정 문구는 붙이지 않는다.
const movementGlossary: readonly { term: string; description: string }[] = [
  {
    term: movementTerms.frontalTilt,
    description: `각 걸음의 중간 입각기 프레임에서 기준 자세 대비 정강이가 좌우로 기운 각도(°)를 구해 걸음 전체에서 평균한 값입니다. ${movementTerms.frontalTiltSign}.`,
  },
  {
    term: movementTerms.sagittalRange,
    description:
      '입각기 동안 정강이가 앞뒤로 회전한 각도의 범위(최대−최소, °)를 걸음별로 구해 중앙값을 취한 값입니다.',
  },
  {
    term: movementTerms.transverseRange,
    description:
      '입각기 동안 정강이가 수평면에서 회전한 각도의 범위(°)를 걸음별로 구해 중앙값을 취한 값입니다.',
  },
  {
    term: movementTerms.swingPeakAngularVelocity,
    description:
      '같은 발의 연속된 접촉 사이(유각기)에서 정강이 각속도의 최댓값(°/s)을 구간별로 구해 중앙값을 취한 값입니다.',
  },
  {
    term: movementTerms.windowCount,
    description:
      'IMU를 사용할 수 있었던 접촉 창(유효 걸음)의 수입니다. 0이면 이 발의 네 지표는 모두 제공되지 않습니다.',
  },
  {
    term: movementTerms.imuCoverage,
    description: `양발의 저장 프레임 중 IMU 가속도·각속도 값이 모두 있는 프레임의 비율(0~1)입니다. ${MOVEMENT_FOOT_UNAVAILABLE}`,
  },
  {
    term: movementTerms.referenceMethod,
    description: `정강이 각도의 기준(0점)을 잡은 방법입니다. ${referenceMethodLabels.QUIET_STANDING} 또는 ${referenceMethodLabels.FIRST_STANCE}으로 표시되며, 기준을 잡지 못하면 발 값을 제공하지 않습니다.`,
  },
];

const measurementStatuses: readonly MeasurementStatus[] = [
  'CREATED',
  'MEASURING',
  'PROCESSING',
  'COMPLETED',
  'CANCELLED',
  'FAILED',
];
const qualityLevels: readonly QualityLevel[] = ['GOOD', 'ACCEPTABLE', 'POOR'];
const receiverStates: readonly ReceiverUploadState[] = [
  'STREAMING',
  'UPLOADING',
  'UPLOAD_COMPLETE',
];

const MOVEMENT_GLOSSARY_TITLE = movementTerms.cardTitle;

export function HelpPage() {
  return (
    <div className="page-stack help-page">
      <PageHeader
        eyebrow="HELP"
        title="도움말"
        description="측정 절차와 화면에 나오는 용어를 설명합니다. 본 서비스는 의료 진단을 제공하지 않습니다."
      />

      <nav aria-label="도움말 목차" className="help-toc">
        <a href="#help-procedure">측정 절차</a>
        <a href="#help-receiver">세션 ID·수신기</a>
        <a href="#help-quality">데이터 품질 플래그</a>
        <a href="#help-patterns">관찰 패턴</a>
        <a href="#help-terms">용어 안내</a>
        <a href="#help-movement">움직임 분석</a>
        <a href="#help-medical">의료 안내</a>
      </nav>

      <section className="content-card" aria-labelledby="help-procedure" id="help-procedure-card">
        <div className="section-heading">
          <div>
            <p className="eyebrow">PROCEDURE</p>
            <h2 id="help-procedure">측정 절차</h2>
          </div>
        </div>
        <ol className="help-steps">
          <li>
            <span>1</span>
            <div>
              <strong>인솔 등록</strong>
              <p>
                <Link to="/devices">내 인솔</Link>에서 왼발(LEFT)과 오른발(RIGHT) 인솔을 각각
                등록합니다. 활성 보정이 있는 인솔만 측정에 사용할 수 있습니다.
              </p>
            </div>
          </li>
          <li>
            <span>2</span>
            <div>
              <strong>새 측정 준비</strong>
              <p>
                <Link to="/measurements/new">새 측정</Link>에서 양쪽 인솔과 전송률을 선택합니다.
                {SAMPLE_RATE_OPTIONS.map(
                  (rate) =>
                    ` ${sampleRateLabels[rate].title}: ${sampleRateLabels[rate].description}`,
                ).join('')}
              </p>
            </div>
          </li>
          <li>
            <span>3</span>
            <div>
              <strong>수신기 연결</strong>
              <p>
                측정 화면의 세션 ID를 복사해 수신기 프로그램에 <code>--session-id</code>로
                전달합니다. 수신기가 인솔 데이터를 모아 서버로 보냅니다.
              </p>
            </div>
          </li>
          <li>
            <span>4</span>
            <div>
              <strong>측정 시작과 걷기</strong>
              <p>
                측정 시작을 누른 뒤 평소처럼 걷습니다. 화면에서 양발 히트맵, 연결 상태, 데이터 품질,
                경과 시간을 확인할 수 있습니다.
              </p>
            </div>
          </li>
          <li>
            <span>5</span>
            <div>
              <strong>측정 종료와 결과 확인</strong>
              <p>
                측정 종료를 누르면 분석이 시작되고 완료되면 결과 화면이 열립니다. 결과는{' '}
                <Link to="/history">기록</Link>에서 다시 볼 수 있습니다.
              </p>
            </div>
          </li>
        </ol>
      </section>

      <section className="content-card" aria-labelledby="help-receiver">
        <div className="section-heading">
          <div>
            <p className="eyebrow">SESSION ID · RECEIVER</p>
            <h2 id="help-receiver">세션 ID와 수신기 안내</h2>
          </div>
        </div>
        <div className="help-columns">
          <div>
            <h3>세션 ID</h3>
            <p>
              측정 세션마다 부여되는 고유 식별자입니다. 수신기가 어느 세션에 데이터를 보낼지
              결정하는 기준이므로 측정 화면의 <strong>세션 ID 복사</strong> 버튼으로 정확히 복사해
              사용하세요.
            </p>
          </div>
          <div>
            <h3>수신기 상태</h3>
            <p>측정 중 화면에 표시되는 수신기 업로드 상태의 의미입니다.</p>
            <dl className="help-glossary help-glossary--compact">
              {receiverStates.map((state) => (
                <div key={state}>
                  <dt>{receiverStateLabels[state]}</dt>
                  <dd>
                    {state === 'STREAMING'
                      ? '수신기가 인솔 데이터를 실시간으로 서버에 보내고 있습니다.'
                      : state === 'UPLOADING'
                        ? '측정을 마친 뒤 남은 배치를 서버로 보내고 있습니다.'
                        : '모든 배치 업로드가 끝나 분석을 시작할 수 있습니다.'}
                  </dd>
                </div>
              ))}
            </dl>
          </div>
        </div>
      </section>

      <section className="content-card" aria-labelledby="help-quality">
        <div className="section-heading">
          <div>
            <p className="eyebrow">DATA QUALITY</p>
            <h2 id="help-quality">데이터 품질 플래그 용어집</h2>
            <p>
              플래그는 측정 데이터의 상태를 설명하며 걸음이나 건강 상태에 대한 판단이 아닙니다.
              플래그가 많으면 재측정이 도움이 될 수 있습니다.
            </p>
          </div>
        </div>
        <dl className="help-glossary help-glossary--compact">
          {qualityLevels.map((level) => (
            <div key={level}>
              <dt>품질 {qualityLabels[level]}</dt>
              <dd>
                {level === 'GOOD'
                  ? '분석에 충분한 데이터가 수신되었습니다.'
                  : level === 'ACCEPTABLE'
                    ? '일부 플래그가 있어 결과를 해석할 때 참고가 필요합니다.'
                    : '데이터가 부족하거나 끊김이 많아 결과의 신뢰도가 제한됩니다.'}
              </dd>
            </div>
          ))}
        </dl>
        <dl className="help-glossary">
          {qualityFlagCodes.map((flag) => (
            <div key={flag}>
              <dt>
                <code>{flag}</code>
              </dt>
              <dd>{qualityFlagLabel(flag)}</dd>
            </div>
          ))}
        </dl>
      </section>

      <section className="content-card" aria-labelledby="help-patterns">
        <div className="section-heading">
          <div>
            <p className="eyebrow">OBSERVED PATTERNS</p>
            <h2 id="help-patterns">관찰 패턴 용어집</h2>
            <p>
              규칙 기반 분석이 걸음마다 확인하는 6가지 경향입니다. 패턴은 관찰된 신호 분포를
              설명하며 질환 유무를 확정하지 않습니다.
            </p>
          </div>
        </div>
        <ul className="help-patterns">
          {observationPatternCodes.map((code) => (
            <li key={code}>
              <span className="help-pattern__icon">
                <Icon name={patternIcons[code]} />
              </span>
              <div>
                <strong>{patternCodeLabel(code)}</strong>
                <p>{patternDescriptions[code]}</p>
              </div>
            </li>
          ))}
        </ul>
        <h3>관찰 단계</h3>
        <dl className="help-glossary help-glossary--compact">
          {observationLevelOrder.map((level) => (
            <div key={level}>
              <dt>{observationLevelLabels[level]}</dt>
              <dd>
                {level === 'REPEATEDLY_OBSERVED'
                  ? '유효 걸음 중 해당 경향이 나타난 비율이 높아 여러 걸음에서 반복된 경우입니다.'
                  : level === 'PARTIALLY_OBSERVED'
                    ? '일부 걸음에서만 해당 경향이 나타난 경우입니다.'
                    : '이번 측정에서 해당 경향이 기준 비율에 이르지 않은 경우입니다.'}
              </dd>
            </div>
          ))}
        </dl>
      </section>

      <section className="content-card" aria-labelledby="help-terms">
        <div className="section-heading">
          <div>
            <p className="eyebrow">GLOSSARY</p>
            <h2 id="help-terms">용어 안내</h2>
          </div>
        </div>
        <dl className="help-glossary">
          <div>
            <dt>{resultTerms.signalShare}</dt>
            <dd>
              한 발 안에서 각 센서가 차지하는 신호의 비율(%)입니다. 센서 값은 보정된 압력이 아니라
              세션 ADC 최댓값 기준의 상대 신호입니다.
            </dd>
          </div>
          <div>
            <dt>{resultTerms.peakSignal}</dt>
            <dd>측정 동안 한 발에서 관찰된 가장 큰 상대 센서 신호입니다.</dd>
          </div>
          <div>
            <dt>{resultTerms.estimatedCop}</dt>
            <dd>
              센서 신호를 가중 평균해 추정한 발 안의 위치(x, y)입니다. 실제 압력 측정값이 아닌
              추정치입니다.
            </dd>
          </div>
          <div>
            <dt>{resultTerms.totalSignal}</dt>
            <dd>
              한 발의 모든 센서 신호를 더한 값으로, 접촉 여부와 상대적인 하중 흐름을 볼 때
              사용합니다.
            </dd>
          </div>
          <div>
            <dt>좌우 신호 비율</dt>
            <dd>
              각 발의 접촉 프레임 평균 총 신호를 L, R이라 할 때 L/(L+R)×100으로 계산한 상대 신호
              비율(%)입니다. 힘이나 체중이 아니며 두 값의 합은 100입니다. 한쪽 발에 접촉 구간이
              없거나 rule-v1.3.0 이전 결과면 제공되지 않습니다.
            </dd>
          </div>
          <div>
            <dt>스트라이드 시간(추정)</dt>
            <dd>
              같은 발의 연속된 접촉 구간 시작 사이 간격(ms)의 중앙값으로, 좌우 값의 평균을 함께
              표시합니다. 접촉 구간 기반 추정값이며 임상 검증된 보행 주기가 아닙니다. 접촉 구간이
              2개 미만이거나 rule-v1.3.0 이전 결과면 제공되지 않습니다.
            </dd>
          </div>
          <div>
            <dt>측정 상태</dt>
            <dd>
              {measurementStatuses.map((status) => measurementStatusLabels[status]).join(' → ')}
              <br />
              준비됨에서 시작하면 측정 중이 되고, 종료하면 분석 중을 거쳐 완료됩니다. 취소나 실패 시
              결과가 생성되지 않습니다.
            </dd>
          </div>
        </dl>
      </section>

      <section className="content-card help-movement" aria-labelledby="help-movement">
        <div className="section-heading">
          <div>
            <p className="eyebrow">MOVEMENT</p>
            <h2 id="help-movement">{MOVEMENT_GLOSSARY_TITLE}</h2>
            <p>
              결과 화면의 {movementTerms.cardTitle} 카드에 나오는 값입니다. IMU 보드는 인솔이 아닌
              정강이에 장착되므로 정강이 분절의 움직임만 표시합니다.
            </p>
          </div>
          <StatusBadge tone="neutral">{movementTerms.badge}</StatusBadge>
        </div>
        <dl className="help-glossary">
          {movementGlossary.map((entry) => (
            <div key={entry.term}>
              <dt>{entry.term}</dt>
              <dd>{entry.description}</dd>
            </div>
          ))}
        </dl>
        <p className="help-disclaimer">
          <Icon name="shield" />
          <span>{MOVEMENT_DISCLAIMER}</span>
        </p>
        <p className="help-note">{MOVEMENT_UNAVAILABLE}</p>
      </section>

      <aside className="safety-card" aria-labelledby="help-medical">
        <Icon name="shield" />
        <div>
          <h2 id="help-medical">의료 안내</h2>
          <p>
            바른걸음은 관찰된 족압 패턴을 이해하도록 돕는 서비스이며 의료 진단을 제공하지 않습니다.
            결과는 질환을 확정하지 않고 관찰된 패턴, 데이터 품질, 재측정 필요성을 설명합니다.
          </p>
          <ul>
            <li>통증이나 불편함이 지속되면 전문가 상담을 권장합니다.</li>
            <li>운동 가이드는 치료나 교정 효과를 보장하지 않으며, 불편하면 즉시 중단하세요.</li>
            <li>데이터 품질이 낮은 결과는 재측정 후 다시 확인해 주세요.</li>
          </ul>
        </div>
      </aside>
    </div>
  );
}
