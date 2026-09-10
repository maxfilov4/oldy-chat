"""Exercise actual ByeDPI against a local TLS/SNI filter, not a mock desync engine.
This is a controlled filtering test; it does not certify any real carrier."""
import array,os,socket,ssl,subprocess,sys,tempfile,threading,time
from pathlib import Path

engine=Path(sys.argv[1]).resolve()
with tempfile.TemporaryDirectory(prefix='oldi-dpi-') as tmp:
    root=Path(tmp);alive=True;protected=[];filtered=[]
    subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(root/'key'),'--out',str(root/'cert'),'-days','1','-subj','/CN=www.youtube.com','-addext','subjectAltName=DNS:www.youtube.com'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
    tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.load_cert_chain(root/'cert',root/'key')
    upstream=socket.create_server(('127.0.0.1',0));upstream.settimeout(.5)
    def echo():
        while alive:
            try:c,_=upstream.accept()
            except (TimeoutError,OSError):continue
            try:
                with tls.wrap_socket(c,server_side=True) as s:
                    data=s.recv(10)
                    s.sendall(data)
            except (OSError,ssl.SSLError):c.close()
    threading.Thread(target=echo,daemon=True).start()
    gate=socket.create_server(('127.0.0.1',0));gate.settimeout(.5)
    def pump(a,b):
        try:
            while chunk:=a.recv(65536):b.sendall(chunk)
        except OSError:pass
        finally:
            try:b.shutdown(socket.SHUT_WR)
            except OSError:pass
    def exact(s,n):
        b=b''
        while len(b)<n:
            c=s.recv(n-len(b))
            if not c:raise OSError('EOF')
            b+=c
        return b
    def filter_conn(c):
        try:
            c.settimeout(10);header=exact(c,5);record=exact(c,int.from_bytes(header[3:5],'big'))
            if b'www.youtube.com' in record:
                filtered.append('blocked');c.close();return
            filtered.append('split')
            with socket.create_connection(upstream.getsockname(),timeout=8) as server:
                server.sendall(header+record);t=threading.Thread(target=pump,args=(server,c),daemon=True);t.start();pump(c,server);t.join(10)
        except OSError:pass
        finally:c.close()
    def filtering():
        while alive:
            try:c,_=gate.accept();threading.Thread(target=filter_conn,args=(c,),daemon=True).start()
            except (TimeoutError,OSError):pass
    threading.Thread(target=filtering,daemon=True).start()
    protector=socket.socket(socket.AF_UNIX);protector.bind(str(root/'protect'));protector.listen(64);protector.settimeout(.5)
    def protect():
        while alive:
            try:c,_=protector.accept()
            except (TimeoutError,OSError):continue
            with c:
                _,ancillary,_,_=c.recvmsg(1,socket.CMSG_SPACE(4))
                fds=array.array('i')
                for level,kind,data in ancillary:
                    if level==socket.SOL_SOCKET and kind==socket.SCM_RIGHTS:fds.frombytes(data)
                assert len(fds)==1
                for fd in fds:os.close(fd)
                protected.append(1);c.sendall(b'\x01')
    threading.Thread(target=protect,daemon=True).start()
    env=dict(os.environ,OLDI_DPI_SOCKET=str(root/'data'))
    process=subprocess.Popen([str(engine),'--ip','127.0.0.1','--no-domain','--no-udp','--protect-path',str(root/'protect'),'--tlsrec','1+s','--split','1+s','--auto','torst,ssl_err','--disorder','1','--tlsrec','1+s','--auto','torst,ssl_err','--split','2'],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    try:
        end=time.monotonic()+5
        while not (root/'data').exists() and process.poll() is None and time.monotonic()<end:time.sleep(.02)
        assert process.poll() is None,'DPI process failed: '+process.communicate()[1].decode(errors='replace')
        context=ssl.create_default_context(cafile=str(root/'cert'))
        try:
            with context.wrap_socket(socket.create_connection(gate.getsockname(),timeout=10),server_hostname='www.youtube.com') as client:client.sendall(b'hello')
            raise AssertionError('Control connection unexpectedly bypassed the filter')
        except (ssl.SSLError,OSError):pass
        client=socket.socket(socket.AF_UNIX);client.settimeout(15);client.connect(str(root/'data'));client.sendall(b'\x05\x01\x00');assert exact(client,2)==b'\x05\x00'
        client.sendall(b'\x05\x01\x00\x01'+socket.inet_aton('127.0.0.1')+gate.getsockname()[1].to_bytes(2,'big'))
        response=exact(client,4);assert response[:2]==b'\x05\x00',response
        exact(client,(4 if response[3]==1 else 16)+2)
        with context.wrap_socket(client,server_hostname='www.youtube.com') as secured:
            secured.sendall(b'hello');assert exact(secured,5)==b'hello'
        assert 'blocked' in filtered and 'split' in filtered and protected
        print('OLDI_DPI_PASS: direct TLS rejected by SNI filter; actual ByeDPI split TLS succeeded; certificate verified; protected-descriptor broker used; private Unix listener.')
    finally:
        process.terminate()
        try:process.wait(3)
        except subprocess.TimeoutExpired:process.kill();process.wait()
        alive=False;gate.close();upstream.close();protector.close()
