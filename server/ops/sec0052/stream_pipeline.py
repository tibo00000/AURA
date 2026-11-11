"""Byte-only streaming pipeline, no archive/plaintext file and no automatic SSH.

All argv must be prepared and approved by the operator; this module has no CLI.
stderr is drained to hashes, never printed or persisted. A failed second pass may
have written into the isolated disposable restore volume: never accept that volume.
"""
import hashlib
import subprocess
import threading
import time

class PipelineFailed(Exception):
    def __init__(self, receipt):
        self.receipt=receipt
        super().__init__('stream_pipeline_rejected')

def run_pass(producer, decryptor, consumer, expected_sha256, *, timeout=90):
    if len(expected_sha256)!=64 or any(c not in '0123456789abcdef' for c in expected_sha256):
        raise ValueError('invalid_expected_hash')
    processes=[]; drains=[]; stderr={}; output=bytearray(); expired=threading.Event()
    def drain(stream, label, keep=False):
        digest=hashlib.sha256(); size=0
        while chunk:=stream.read(65536):
            digest.update(chunk); size+=len(chunk)
            if keep and len(output)<65536: output.extend(chunk[:65536-len(output)])
        stderr[label]=dict(bytes=size,sha256=digest.hexdigest())
    def launch(argv, label, **kwargs):
        p=subprocess.Popen(argv,stderr=subprocess.PIPE,**kwargs); processes.append((label,p))
        thread=threading.Thread(target=drain,args=(p.stderr,label),daemon=True); thread.start(); drains.append(thread)
        return p
    def stop():
        expired.set()
        for _,p in processes:
            if p.poll() is None: p.kill()
    timer=threading.Timer(timeout,stop)
    digest=hashlib.sha256(); count=0; pipe_failed=False; started=time.monotonic()
    try:
        source=launch(producer,'ssh',stdin=subprocess.DEVNULL,stdout=subprocess.PIPE)
        age=launch(decryptor,'age',stdin=subprocess.PIPE,stdout=subprocess.PIPE)
        sink=launch(consumer,'consumer',stdin=age.stdout,stdout=subprocess.PIPE)
        age.stdout.close()
        thread=threading.Thread(target=drain,args=(sink.stdout,'consumer_stdout',True),daemon=True); thread.start(); drains.append(thread)
        timer.start()
        while chunk:=source.stdout.read(65536):
            digest.update(chunk); count+=len(chunk)
            if not pipe_failed:
                try: age.stdin.write(chunk); age.stdin.flush()
                except (BrokenPipeError,OSError): pipe_failed=True
        try: age.stdin.close()
        except (BrokenPipeError,OSError): pipe_failed=True
        for _,p in processes: p.wait(timeout=timeout+5)
        for thread in drains: thread.join(timeout=5)
        receipt=dict(codes={label:p.returncode for label,p in processes},ciphertext_bytes=count,
                     ciphertext_sha256=digest.hexdigest(),hash_equal=digest.hexdigest()==expected_sha256,
                     broken_pipe=pipe_failed,timed_out=expired.is_set(),stderr=stderr,
                     elapsed_seconds=round(time.monotonic()-started,3))
        receipt['ok']=(not pipe_failed and not expired.is_set() and receipt['hash_equal']
                       and all(p.returncode==0 for _,p in processes) and not any(t.is_alive() for t in drains))
        if not receipt['ok']: raise PipelineFailed(receipt)
        return receipt,bytes(output)
    finally:
        timer.cancel()
        for _,p in processes:
            if p.poll() is None: p.kill(); p.wait(timeout=5)
            for stream in (p.stdin,p.stdout,p.stderr):
                if stream and not stream.closed:
                    try: stream.close()
                    except OSError: pass

def restore_two_reads(producer, decryptor, validator, extractor, expected_sha256):
    first,_=run_pass(producer,decryptor,validator,expected_sha256)
    second,_=run_pass(producer,decryptor,extractor,expected_sha256)
    return dict(ok=True,first=first,second=second,permanent_client_archive=False)
