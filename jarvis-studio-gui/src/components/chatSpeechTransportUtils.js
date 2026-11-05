import { stripMarkdownForSpeech } from "../brain/formatting";

const CHARS_PER_SECOND = 14;

/** Estimate a text offset; Android TTS exposes no audio clock or seek API. */
export function approximateSpeechOffset(text, seconds) {
  const clean = stripMarkdownForSpeech(text);
  const requested = Math.round(Math.max(0, seconds) * CHARS_PER_SECOND);
  return Math.min(clean.length, requested);
}

/** Return the base offset for the active utterance, or null when it is unrelated. */
export function speechTextStartOffset(eventText, fullText, expectedSuffix = null) {
  if (eventText === fullText) return 0;
  if (expectedSuffix && eventText === expectedSuffix && fullText.endsWith(eventText)) {
    return fullText.length - eventText.length;
  }
  return null;
}
