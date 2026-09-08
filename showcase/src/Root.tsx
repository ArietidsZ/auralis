import {Composition} from 'remotion';
import {AuralisShowcase} from './Showcase';
export const Root = () => (
  <Composition id="AuralisShowcase" component={AuralisShowcase}
    width={1600} height={900} fps={30} durationInFrames={540}
    defaultProps={{reducedMotion: false}} />
);
