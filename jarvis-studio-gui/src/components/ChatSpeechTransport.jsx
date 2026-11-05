import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { getActiveSpeech, subscribeSpeechLifecycle, webSpeechTTS } from "../brain/platform/webspeech";
import { stripMarkdownForSpeech } from "../brain/formatting";
import { approximateSpeechOffset, speechTextStartOffset } from "./chatSpeechTransportUtils";
import "./chat-speech-transport.css";

const SKIP_SECONDS = 5;
const CHARS_PER_SECOND = 14;

/** Controls the shared TTS utterance when it matches the latest visible reply. */
export default function ChatSpeechTransport({ message }) {
  const text = useMemo(() => stripMarkdownForSpeech(message || ""), [message]);
  const [position, setPosition] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [paused, setPaused] = useState(false);
  const run = useRef(0);
  const activeId = useRef(null);
  const expectedText = useRef(null);
  const startAt = useRef(0);
  const beganAt = useRef(0);

  const startSuffix = useCallback(async (offset) => {
    const start = Math.max(0, Math.min(text.length, offset));
    const token = ++run.current;
    activeId.current = null;
    expectedText.current = text.slice(start);
    startAt.current = start;
    beganAt.current = Date.now();
    setPosition(start);
    setPaused(false);
    if (start >= text.length) { setPlaying(false); return; }
    setPlaying(true);
    try {
      await webSpeechTTS.speak(expectedText.current);
    } finally {
      if (run.current === token) {
        setPlaying(false);
        setPaused(false);
        setPosition(text.length);
        activeId.current = null;
      }
    }
  }, [text]);

  useEffect(() => {
    const adopt = (event) => {
      const offset = speechTextStartOffset(event.text, text, expectedText.current);
      if (event.type === "start" && offset !== null) {
        if (offset === 0 && event.text === text) run.current += 1;
        activeId.current = event.id;
        startAt.current = offset;
        beganAt.current = Date.now();
        expectedText.current = null;
        setPosition(0);
        setPlaying(true);
        setPaused(false);
      } else if (event.type === "start") {
        activeId.current = null;
        setPlaying(false);
      } else if ((event.type === "end" || event.type === "stop") && event.id === activeId.current) {
        activeId.current = null;
        setPlaying(false);
      }
    };
    const unsubscribe = subscribeSpeechLifecycle(adopt);
    const active = getActiveSpeech();
    if (active?.type === "start" && active.text === text) adopt(active);
    return unsubscribe;
  }, [text]);

  useEffect(() => {
    if (!playing) return undefined;
    const timer = setInterval(() => {
      const elapsed = (Date.now() - beganAt.current) / 1000;
      setPosition(Math.min(text.length, startAt.current + approximateSpeechOffset(text.slice(startAt.current), elapsed)));
    }, 250);
    return () => clearInterval(timer);
  }, [playing, text]);

  const currentPosition = () => playing
    ? Math.min(text.length, startAt.current + approximateSpeechOffset(text.slice(startAt.current), (Date.now() - beganAt.current) / 1000))
    : position;
  const pause = async () => {
    if (!playing) return;
    const offset = currentPosition();
    ++run.current;
    activeId.current = null;
    await webSpeechTTS.stop();
    setPosition(offset);
    setPlaying(false);
    setPaused(true);
  };
  const skip = async (delta) => {
    const offset = currentPosition();
    const token = ++run.current;
    activeId.current = null;
    await webSpeechTTS.stop();
    if (run.current !== token) return;
    await startSuffix(Math.max(0, Math.min(text.length, offset + delta * CHARS_PER_SECOND)));
  };

  if (!text) return null;
  return (
    <div className="chat-speech-transport" role="group" aria-label="Reply speech controls">
      <button type="button" onClick={() => (playing ? void pause() : void startSuffix(position >= text.length ? 0 : position))}>
        {playing ? "Pause" : paused || position > 0 ? "Resume" : "Play"}
      </button>
      <button type="button" onClick={() => void skip(-SKIP_SECONDS)} aria-label="Back approximately five seconds">−5s</button>
      <button type="button" onClick={() => void skip(SKIP_SECONDS)} aria-label="Forward approximately five seconds">+5s</button>
      <span aria-live="polite">{playing ? "Speaking" : paused ? "Paused" : ""}</span>
      <small>Android skips estimate text position; live TTS has no audio timeline.</small>
    </div>
  );
}
