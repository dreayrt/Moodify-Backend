"""Versioned Essentia descriptors. Semantic indices are estimates, never model ground truth."""
import math
import os
from pathlib import Path
import tempfile
import threading
from fastapi import FastAPI, File, HTTPException, UploadFile

VERSION = 'essentia-descriptors-v2'
try:
    import essentia.standard as es
except ImportError:
    es = None

app = FastAPI(title='Moodify audio descriptors', version='2.0.0')
capacity = threading.BoundedSemaphore(1)

def normalized_energy(total, count):
    if count <= 0 or not math.isfinite(total) or total <= 0: raise ValueError('silent_or_invalid_audio')
    # Mean square -> dBFS. Preserve mastering differences instead of clipping RMS/0.02 to one.
    dbfs = 10 * math.log10(total / count)
    return round(max(0.0, min(1.0, (dbfs + 60.0) / 60.0)), 6)

def normalized_danceability(raw):
    if not math.isfinite(raw) or raw < 0: raise ValueError('invalid_danceability')
    # Essentia's documented normal range is approximately 0..3, not 0..1.
    # Smooth monotonic scaling avoids losing every raw value above one.
    return round(raw / (raw + 1.0), 6)

@app.get('/health')
def health():
    if es is None: raise HTTPException(503, 'Essentia is unavailable; synthetic fallback is disabled')
    return {'status':'ok','engine':'essentia','version':VERSION,'semanticFeatures':'estimated'}

@app.post('/analyze')
def analyze(file: UploadFile = File(...)):
    if es is None: raise HTTPException(503, 'Essentia is unavailable')
    if not capacity.acquire(blocking=False): raise HTTPException(429, 'Analyzer capacity reached', headers={'Retry-After':'30'})
    name = None
    try:
        with tempfile.NamedTemporaryFile(suffix='.mp3', delete=False) as out:
            name=out.name;size=0
            while chunk:=file.file.read(1024*1024):
                size+=len(chunk)
                if size>64*1024*1024: raise HTTPException(413, 'Audio exceeds analysis limit')
                out.write(chunk)
        audio=es.MonoLoader(filename=name,sampleRate=44100)()
        if not len(audio) or len(audio)>44100*1200: raise HTTPException(422,'Unsupported audio duration')
        bpm,_,_,_,_=es.RhythmExtractor2013(method='multifeature')(audio)
        key,scale,strength=es.KeyExtractor()(audio)
        raw_dance,_=es.Danceability()(audio)
        total=float(es.Energy()(audio))
        energy=normalized_energy(total,len(audio))
        danceability=normalized_danceability(float(raw_dance))
        centroid=float(es.SpectralCentroidTime()(audio))
        spectral=max(0.0,min(1.0,centroid/4000.0))
        valence=round(.4*spectral+.3*danceability+.3*(.6 if scale.lower()=='major' else .4),6)
        acousticness=round(max(.05,min(.95,1-energy*.7-(.2 if bpm>125 else 0))),6)
        if not all(math.isfinite(v) for v in [float(bpm),energy,danceability,valence,acousticness]) or bpm<=0:
            raise ValueError('invalid_descriptors')
        return {'bpm':round(float(bpm),3),'keySignature':f'{key} {scale.capitalize()}',
            'keyConfidence':float(strength),'energy':energy,'danceability':danceability,
            'valence':valence,'acousticness':acousticness,'durationMs':round(len(audio)*1000/44100),
            'version':VERSION,'assessment':'estimated_not_listener_verified',
            'raw':{'danceability':float(raw_dance),'meanSquare':total/len(audio),'spectralCentroidHz':centroid}}
    except HTTPException: raise
    except Exception as error:
        raise HTTPException(422, 'Audio analysis failed: '+type(error).__name__) from error
    finally:
        if name:Path(name).unlink(missing_ok=True)
        capacity.release()
