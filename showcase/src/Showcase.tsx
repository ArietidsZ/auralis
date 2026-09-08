import type {CSSProperties} from 'react';
import {AbsoluteFill, Easing, Img, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';

const c = {pine: '#142128', mint: '#63B3A4', paper: '#F6F8F7', accent: '#216B62', muted: '#A9C0B7'};
const clamp = {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'} as const;
const ease = (frame: number, start: number, length: number) => interpolate(
  frame, [start, start + length], [0, 1], {...clamp, easing: Easing.bezier(0.22, 1, 0.36, 1)},
);
const row: CSSProperties = {display: 'flex', alignItems: 'center'};

const Mic = ({stop = false}: {stop?: boolean}) => (
  <svg width="29" height="29" viewBox="0 0 28 28" fill="none" aria-hidden="true">
    {stop ? <rect x="7" y="7" width="14" height="14" rx="3" fill="currentColor" /> : <>
      <rect x="10" y="3" width="8" height="15" rx="4" stroke="currentColor" strokeWidth="2" />
      <path d="M6 13a8 8 0 0 0 16 0M14 21v4M10 25h8" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </>}
  </svg>
);

export const AuralisShowcase = ({reducedMotion}: {reducedMotion: boolean}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const reveal = (start: number, length = 24) => reducedMotion ? ease(frame, start, 6) : ease(frame, start, length);
  const phoneIn = reducedMotion ? 1 : spring({frame: Math.max(0, frame - 30), fps, durationInFrames: 40,
    config: {damping: 24, stiffness: 150, mass: 1}});
  const listening = frame >= 95 && frame < 235;
  const translating = frame >= 235 && frame < 315;
  const speaking = frame >= 315 && frame < 445;
  const active = listening || translating || speaking;
  const phase = listening ? '聆听中' : translating ? '翻译中' : speaking ? '播放中' : frame >= 445 ? '准备下一句' : '准备开始';
  const step = listening ? 0 : translating ? 1 : speaking ? 2 : frame >= 445 ? 3 : -1;
  // A deterministic interface demonstration; this envelope is not recorded audio.
  const level = listening ? (reducedMotion ? 0.48 : 0.3 + 0.24 * Math.sin(frame / 9) ** 2 + 0.17 * Math.sin(frame / 17) ** 2) : 0;
  const messageData = [
    {at: 225, label: '普通话 · 原文', text: '我们明天下午两点在车站见。', accent: false},
    {at: 305, label: 'English · 译文', text: "Let’s meet at the station at two tomorrow afternoon.", accent: true},
  ];
  const titleIn = reveal(6, 30);
  const subtitleIn = reveal(18, 28);
  const outro = reveal(452, 28);

  return <AbsoluteFill style={{background: c.pine, color: c.paper,
    fontFamily: 'system-ui, -apple-system, BlinkMacSystemFont, "PingFang SC", sans-serif', overflow: 'hidden'}}>
    <AbsoluteFill style={{background: 'radial-gradient(ellipse at 80% 35%, #36675A50, transparent 55%)'}} />
    <div style={{position: 'absolute', left: 96, top: 76, ...row, gap: 16}}>
      <Img src={staticFile('mark.svg')} style={{width: 56, height: 56}} />
      <span style={{fontSize: 31, fontWeight: 650, letterSpacing: -0.7}}>Auralis</span>
    </div>

    <div style={{position: 'absolute', left: 104, top: 246, width: 790,
      opacity: titleIn, transform: `translateY(${reducedMotion ? 0 : 22 * (1 - titleIn)}px)`}}>
      <div style={{fontSize: 81, lineHeight: 1.08, fontWeight: 620, letterSpacing: -3.8}}>Your voice.<br />
        <span style={{color: c.mint}}>Across languages.</span>
      </div>
      <div style={{marginTop: 30, fontSize: 25, lineHeight: 1.55, color: '#C9D9D2', opacity: subtitleIn}}>
        Offline speech translation.<br />Native on Android and iOS.
      </div>
      <div style={{...row, marginTop: 49, gap: 12, opacity: reveal(75)}}>
        {['Recognize', 'Translate', 'Speak'].map((label, index) => {
          const selected = step >= index;
          return <div key={label} style={{...row, gap: 12}}>
            {index > 0 && <span style={{color: '#617D72', fontSize: 20}}>→</span>}
            <div style={{...row, gap: 9, border: `1px solid ${selected ? '#63B3A480' : '#91AA9F35'}`,
              borderRadius: 24, padding: '10px 17px', color: selected ? c.paper : '#93ADA2', fontSize: 16}}>
              <span style={{width: 6, height: 6, borderRadius: 8, background: selected ? c.mint : '#5B756A'}} />{label}
            </div>
          </div>;
        })}
      </div>
      <div style={{marginTop: 36, opacity: outro, fontSize: 20, color: c.mint,
        transform: `translateY(${reducedMotion ? 0 : 8 * (1 - outro)}px)`}}>On your device. In your voice.</div>
    </div>

    <div style={{position: 'absolute', left: 1050, top: 61, width: 420, height: 778,
      padding: 9, borderRadius: 46, background: '#293D3A', border: '1px solid #64837770',
      boxShadow: '0 36px 100px #0007', opacity: reveal(30, 25),
      transform: `translateY(${reducedMotion ? 0 : 38 * (1 - phoneIn)}px) scale(${reducedMotion ? 1 : 0.97 + 0.03 * phoneIn})`}}>
      <div style={{height: '100%', borderRadius: 36, overflow: 'hidden', position: 'relative', background: c.paper, color: c.pine}}>
        <div style={{background: c.pine, color: c.paper, padding: '14px 22px 21px'}}>
          <div style={{...row, justifyContent: 'space-between', fontSize: 12, fontWeight: 650, marginBottom: 26}}>
            <span>9:41</span><span style={{letterSpacing: 3}}>● ▰</span>
          </div>
          <div style={{...row, justifyContent: 'space-between'}}>
            <div style={{fontSize: 25, fontWeight: 650, letterSpacing: -0.5}}>Auralis</div>
            <div style={{fontSize: 22, color: '#B6CEC3', letterSpacing: 12}}>↔ ⋮</div>
          </div>
          <div style={{...row, gap: 8, marginTop: 8, minHeight: 20, fontSize: 13, color: c.muted}}>
            <span style={{width: 6, height: 6, borderRadius: 8, background: active ? c.mint : '#6A8176',
              opacity: active && !reducedMotion ? 0.7 + 0.3 * Math.sin(frame / 17) ** 2 : 1}} />{phase}
          </div>
        </div>
        <div style={{...row, gap: 12, margin: '18px 22px', fontSize: 12, color: c.accent}}>
          <span style={{background: '#DFEDE5', borderRadius: 18, padding: '7px 12px'}}>普通话</span>
          <span style={{color: '#8CA398'}}>→</span>
          <span style={{background: '#DFEDE5', borderRadius: 18, padding: '7px 12px'}}>English</span>
        </div>

        <div style={{position: 'absolute', top: 248, left: 34, right: 34,
          textAlign: 'center', opacity: (1 - reveal(225, 12)) * reveal(80, 22)}}>
          <div style={{display: 'flex', gap: 5, height: 68, justifyContent: 'center', alignItems: 'center'}}>
            {Array.from({length: 7}, (_, i) => <div key={i} style={{width: 5, borderRadius: 4,
              height: 8 + level * 57 * (0.35 + 0.65 * Math.sin(i * 0.9 + (reducedMotion ? 0 : frame / 16)) ** 2),
              background: c.accent}} />)}
          </div>
          <div style={{marginTop: 15, fontSize: 17, color: '#667E72'}}>自然表达，从容交流</div>
        </div>

        <div style={{position: 'absolute', top: 220, left: 19, right: 19}}>
          {messageData.map(({at, label, text, accent}) => {
            const amount = reveal(at, 24);
            return <div key={label} style={{marginBottom: 14, padding: '19px 21px 22px', borderRadius: 21,
              border: `1px solid ${accent ? '#BFDACD' : '#DEE7E1'}`, background: accent ? '#E8F2EB' : '#FFFFFF',
              boxShadow: '0 3px 10px #14212805', opacity: amount,
              transform: `translateY(${reducedMotion ? 0 : 13 * (1 - amount)}px)`}}>
              <div style={{fontSize: 11, fontWeight: 650, color: accent ? c.accent : '#7A9084', marginBottom: 12}}>{label}</div>
              <div style={{fontSize: accent ? 21 : 23, lineHeight: 1.48, letterSpacing: -0.25}}>{text}</div>
              {accent && <div style={{...row, gap: 6, fontSize: 11, marginTop: 15, color: c.accent, opacity: reveal(324, 12)}}>
                <span style={{fontSize: 14}}>◖</span>{speaking ? '正在播放' : '播放完成'}
              </div>}
            </div>;
          })}
        </div>

        <div style={{position: 'absolute', left: 22, right: 22, bottom: 34, ...row, justifyContent: 'space-between'}}>
          <div style={{fontSize: 13, color: '#72887D'}}>{active ? '结束传译' : '开始传译'}</div>
          <div style={{width: 65, height: 65, borderRadius: '50%', ...row, justifyContent: 'center',
            background: c.accent, color: c.paper,
            boxShadow: listening ? `0 0 0 ${6 + level * 8}px #216B6210` : '0 0 0 0 #216B6200'}}><Mic stop={active} /></div>
        </div>
        <div style={{position: 'absolute', width: 113, height: 4, bottom: 10, left: 143,
          borderRadius: 4, background: '#142128'}} />
      </div>
    </div>
    <div style={{position: 'absolute', left: 104, bottom: 38, ...row, gap: 18, color: '#9EB7AA', fontSize: 14}}>
      <span>Developer Preview</span><span style={{opacity: 0.45}}>·</span><span>Interface demonstration</span>
    </div>
  </AbsoluteFill>;
};
