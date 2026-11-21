import { t as translateUi } from '@/i18n';
import { needsSignIn, spotifyFailureText, toSpotifyFailure } from '@/data/spotify/failure';
import { openSpotifySignIn } from '@/extension/useSpotifyAuth';
import { stateStyles } from './States';

/** What went wrong with a Spotify call, in the phone's words, with the one action that helps (SpotifyProblem in SpotifyLibraryScreen.kt). */
export function SpotifyProblem({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const failure = toSpotifyFailure(error);
  const signIn = needsSignIn(failure.kind);
  return (
    <div className={stateStyles.state}>
      <p className={stateStyles.title}>{spotifyFailureText(failure.kind)}</p>
      {failure.kind === 'rate_limited' && failure.retryAfterSec ? (
        <p className={stateStyles.detail}>{translateUi("spotify_problem.wait_seconds", { seconds: failure.retryAfterSec })}</p>
      ) : null}
      <button className={stateStyles.button} onClick={signIn ? openSpotifySignIn : onRetry}>
        {signIn ? translateUi("components_music_spotifyproblem_sign_in_with_spotify") : translateUi("components_music_spotifyproblem_try_again")}
      </button>
    </div>
  );
}
