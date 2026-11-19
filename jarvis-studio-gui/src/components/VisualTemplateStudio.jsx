import { useCallback, useEffect, useRef, useState } from "react";
import { androidPlatform } from "../brain/platform";

const clamp = (value, min, max) => Math.max(min, Math.min(max, value));

function cropBetween(a, b) {
  const x = Math.min(a.x, b.x);
  const y = Math.min(a.y, b.y);
  return {
    x,
    y,
    w: Math.abs(a.x - b.x),
    h: Math.abs(a.y - b.y),
  };
}

function newTemplateId() {
  const suffix = globalThis.crypto?.randomUUID?.() || Math.random().toString(36).slice(2);
  return ("icon-" + Date.now().toString(36) + "-" + suffix).replace(/[^A-Za-z0-9_-]/g, "").slice(0, 80);
}

/**
 * User-directed icon learning. The screen preview remains in component memory;
 * the native layer persists only a small app-scoped grayscale fingerprint.
 */
export default function VisualTemplateStudio() {
  const [preview, setPreview] = useState(null);
  const [crop, setCrop] = useState(null);
  const [label, setLabel] = useState("");
  const [templates, setTemplates] = useState([]);
  const [matches, setMatches] = useState([]);
  const [status, setStatus] = useState("");
  const [busy, setBusy] = useState(false);
  const [armed, setArmed] = useState(false);
  const imageRef = useRef(null);
  const anchor = useRef(null);

  const refresh = useCallback(async () => {
    const result = await androidPlatform.visualTemplates.list();
    if (result?.ok) setTemplates(result.templates || []);
    else setStatus(result?.summary || "Could not load saved icon templates.");
  }, []);

  useEffect(() => {
    let active = true;
    androidPlatform.visualTemplates.list().then((result) => {
      if (!active) return;
      if (result?.ok) setTemplates(result.templates || []);
      else setStatus(result?.summary || "Could not load saved icon templates.");
    });
    return () => {
      active = false;
    };
  }, []);

  const pointAt = (event) => {
    const image = imageRef.current;
    if (!image) return null;
    const rect = image.getBoundingClientRect();
    if (!rect.width || !rect.height) return null;
    return {
      x: clamp(Math.round(((event.clientX - rect.left) / rect.width) * 1000), 0, 1000),
      y: clamp(Math.round(((event.clientY - rect.top) / rect.height) * 1000), 0, 1000),
    };
  };

  const startSelection = (event) => {
    if (!preview || busy || event.button !== 0) return;
    const point = pointAt(event);
    if (!point) return;
    event.preventDefault();
    event.currentTarget.setPointerCapture?.(event.pointerId);
    anchor.current = point;
    setCrop({ x: point.x, y: point.y, w: 0, h: 0 });
  };

  const updateSelection = (event) => {
    if (!anchor.current) return;
    const point = pointAt(event);
    if (point) setCrop(cropBetween(anchor.current, point));
  };

  const finishSelection = (event) => {
    if (!anchor.current) return;
    const point = pointAt(event);
    if (point) setCrop(cropBetween(anchor.current, point));
    anchor.current = null;
  };

  const capture = async () => {
    setBusy(true);
    setPreview(null);
    setCrop(null);
    setMatches([]);
    setStatus("Arming the floating capture control…");
    try {
      const result = await androidPlatform.visualTemplates.arm();
      setArmed(Boolean(result?.ok));
      setStatus(result?.summary || "Could not arm screen capture. Check that JARVIS Accessibility is enabled.");
    } finally {
      setBusy(false);
    }
  };

  const loadCaptured = async () => {
    setBusy(true);
    setStatus("Loading the captured game screen…");
    try {
      const result = await androidPlatform.visualTemplates.preview();
      if (!result?.ok || !result.base64 || !result.appPackage || !result.previewToken) {
        setStatus(result?.summary || "No captured screen is waiting. Arm capture, switch to the game, and tap the floating CAPTURE control.");
        return;
      }
      setPreview(result);
      setArmed(false);
      setCrop(null);
      setMatches([]);
      setStatus("Captured " + result.appPackage + ". Drag around one icon or control.");
    } finally {
      setBusy(false);
    }
  };

  const save = async () => {
    if (!preview || !crop || crop.w < 30 || crop.h < 30 || !label.trim()) {
      setStatus("Select a larger icon area and give it a name first.");
      return;
    }
    setBusy(true);
    setStatus("Learning the selected icon on this device…");
    try {
      const result = await androidPlatform.visualTemplates.save({
        id: newTemplateId(),
        label: label.trim(),
        previewToken: preview.previewToken,
        ...crop,
      });
      setStatus(result?.summary || "The icon could not be saved.");
      if (result?.ok) {
        setPreview(null);
        setCrop(null);
        setLabel("");
        await refresh();
      }
    } finally {
      setBusy(false);
    }
  };

  const find = async () => {
    setBusy(true);
    setStatus("Looking for saved icons in the captured game screen…");
    try {
      const result = await androidPlatform.visualTemplates.find(preview.previewToken);
      if (!result?.ok) {
        setStatus(result?.summary || "Could not search the current screen.");
        return;
      }
      setMatches(result.matches || []);
      setStatus(result.summary || "Visual search complete.");
    } finally {
      setBusy(false);
    }
  };

  const remove = async (id) => {
    setBusy(true);
    try {
      const result = await androidPlatform.visualTemplates.delete(id);
      setStatus(result?.summary || "Could not delete that template.");
      if (result?.ok) await refresh();
    } finally {
      setBusy(false);
    }
  };

  const imageUrl = preview?.base64
    ? preview.base64.startsWith("data:") ? preview.base64 : "data:image/jpeg;base64," + preview.base64
    : "";

  return (
    <section className="cz-sec" aria-labelledby="cz-visual-templates-title">
      <h3 id="cz-visual-templates-title">Teach Jarvis an icon</h3>
      <p className="cz-note">
        Arm capture here, switch to RuneScape, and tap the small floating CAPTURE button. Return to JARVIS and load that screenshot. Drag around an icon and name it. The screenshot is temporary; only a compact visual fingerprint is saved on this phone.
      </p>
      <div className="cz-preset-bar">
        <button type="button" className="btn btn--ghost" onClick={capture} disabled={busy}>
          {busy ? "Working…" : "Arm game capture"}
        </button>
        <button type="button" className="btn btn--ghost" onClick={loadCaptured} disabled={busy}>
          Load captured screen
        </button>
        {armed && (
          <button type="button" className="btn btn--ghost" onClick={async () => {
            await androidPlatform.visualTemplates.discard("");
            setArmed(false);
            setStatus("Cancelled the floating capture control.");
          }} disabled={busy}>
            Cancel capture
          </button>
        )}
        <button type="button" className="btn btn--ghost" onClick={find} disabled={busy || !preview || templates.length === 0}>
          Find saved icons in preview
        </button>
      </div>

      {preview && (
        <div className="cz-visual-learn">
          <p className="cz-note">
            App: <strong>{preview.appPackage}</strong>. Drag across the icon; avoid selecting the whole screen.
          </p>
          <div
            style={{
              position: "relative",
              display: "inline-block",
              maxWidth: "100%",
              border: "1px solid var(--line, rgba(255,255,255,.18))",
              borderRadius: 10,
              overflow: "hidden",
              touchAction: "none",
            }}
          >
            <img
              ref={imageRef}
              src={imageUrl}
              alt="Temporary screenshot for selecting a visual icon template"
              draggable={false}
              onPointerDown={startSelection}
              onPointerMove={updateSelection}
              onPointerUp={finishSelection}
              onPointerCancel={finishSelection}
              style={{
                display: "block",
                maxWidth: "100%",
                maxHeight: "48vh",
                width: "auto",
                height: "auto",
                cursor: "crosshair",
                userSelect: "none",
                touchAction: "none",
              }}
            />
            {crop && (
              <div
                aria-hidden="true"
                style={{
                  position: "absolute",
                  pointerEvents: "none",
                  left: (crop.x / 10) + "%",
                  top: (crop.y / 10) + "%",
                  width: (crop.w / 10) + "%",
                  height: (crop.h / 10) + "%",
                  border: "2px solid var(--accent, #00e5ff)",
                  background: "rgba(0,229,255,.14)",
                  boxSizing: "border-box",
                }}
              />
            )}
          </div>
          <label style={{ display: "grid", gap: 6, marginTop: 10 }}>
            <span className="cz-note">Icon name</span>
            <input
              value={label}
              onChange={(event) => setLabel(event.target.value)}
              maxLength={80}
              placeholder="e.g. Inventory, Bank, Attack style"
              disabled={busy}
            />
          </label>
          <p className="cz-note">
            {crop ? "Selection: x " + crop.x + ", y " + crop.y + ", " + crop.w + " × " + crop.h + " / 1000" : "No area selected yet."}
          </p>
          <div className="cz-preset-bar">
            <button type="button" className="btn btn--primary" onClick={save} disabled={busy || !crop || crop.w < 30 || crop.h < 30 || !label.trim()}>
              Save icon locally
            </button>
            <button type="button" className="btn btn--ghost" onClick={async () => {
              await androidPlatform.visualTemplates.discard(preview.previewToken);
              setPreview(null);
              setCrop(null);
              setMatches([]);
            }} disabled={busy}>
              Discard preview
            </button>
          </div>
        </div>
      )}

      {status && <p className="cz-note" role="status">{status}</p>}

      {templates.length > 0 && (
        <div style={{ display: "grid", gap: 8, marginTop: 10 }}>
          <strong>Saved icons ({templates.length})</strong>
          {templates.map((item) => (
            <div key={item.id} className="cz-panel-row">
              <span style={{ flex: 1, minWidth: 0 }}>
                <strong>{item.label}</strong>
                <span className="cz-note" style={{ display: "block", overflowWrap: "anywhere" }}>{item.appPackage}</span>
              </span>
              <button type="button" className="btn btn--ghost btn--sm" onClick={() => remove(item.id)} disabled={busy}>
                Remove
              </button>
            </div>
          ))}
        </div>
      )}

      {matches.length > 0 && (
        <div style={{ display: "grid", gap: 6, marginTop: 10 }}>
          <strong>Possible matches</strong>
          {matches.map((match) => (
            <p key={match.id} className="cz-note">
              {match.label} — {Math.round(match.confidence * 100)}% confidence at ({match.x}, {match.y})
            </p>
          ))}
          <p className="cz-note">Matches are suggestions only. Jarvis does not tap them automatically.</p>
        </div>
      )}
    </section>
  );
}
