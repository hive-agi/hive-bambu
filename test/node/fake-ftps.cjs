'use strict';
const tls = require('node:tls');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const {EventEmitter} = require('node:events');

/** Fake implicit-FTPS server with a TLS control socket and passive TLS data socket. */
async function startFtps() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(),'bambu-fake-tls-'));
  const certificate = path.join(dir,'certificate.pem');
  const privateFile = path.join(dir,'private.pem');
  execFileSync('openssl',['req','-x509','-newkey','rsa:2048','-nodes','-keyout',privateFile,
    '-out',certificate,'-days','1','-subj','/CN=localhost'],{stdio:'ignore'});
  const cert = fs.readFileSync(certificate), key = fs.readFileSync(privateFile);
  const events = new EventEmitter();
  const commands = [];
  const data = Buffer.from('fake-printer-gcode\n');
  const uploaded = new Map();
  const server = tls.createServer({cert,key});
  server.on('secureConnection', socket => {
    socket.write('220 Fake printer FTP ready\r\n');
    let pending = '';
    let passive;
    socket.on('data', bytes => {
      pending += bytes.toString();
      while (pending.includes('\n')) {
        const idx = pending.indexOf('\n');
        const command = pending.slice(0,idx).trim(); pending = pending.slice(idx+1);
        commands.push(command.startsWith('PASS ') ? 'PASS [present]' : command);
        const [verb,...args] = command.split(' ');
        const filename = args.join(' ');
        const reply = (line) => socket.write(line + '\r\n');
        if (verb === 'USER') reply('331 Password required');
        else if (verb === 'PASS') reply('230 Logged in');
        else if (verb === 'FEAT') reply('211 No features');
        else if (verb === 'TYPE' || verb === 'PBSZ' || verb === 'PROT' || verb === 'STRU' || verb === 'OPTS') reply('200 OK');
        else if (verb === 'SIZE') reply(filename === '/existing.gcode' ? `213 ${data.length}` : uploaded.has(filename) ? `213 ${uploaded.get(filename).length}` : '550 No such file');
        else if (verb === 'EPSV' || verb === 'PASV') {
          passive = tls.createServer({cert,key});
          passive.listen(0,'127.0.0.1', () => {
            const port = passive.address().port;
            if (verb === 'EPSV') reply(`229 Entering Extended Passive Mode (|||${port}|)`);
            else reply(`227 Entering Passive Mode (127,0,0,1,${port >> 8},${port & 255})`);
          });
        } else if (verb === 'LIST' || verb === 'MLSD' || verb === 'RETR' || verb === 'STOR') {
          if (!passive) {reply('425 No passive data connection');continue;}
          reply('150 Opening data connection');
          const transfer = passive;
          transfer.once('secureConnection', stream => {
            if (verb === 'RETR') stream.end(data);
            else if (verb === 'LIST') stream.end('-rw-r--r-- 1 owner group 19 Jan 01 00:00 existing.gcode\r\n');
            else if (verb === 'MLSD') stream.end(`type=file;size=${data.length}; existing.gcode\r\n`);
            else { const chunks=[]; stream.on('data', b=>chunks.push(b));stream.on('end',()=>{const payload=Buffer.concat(chunks);uploaded.set(filename,payload);events.emit('upload',payload);}); }
            stream.once('close',()=>{ transfer.close(); reply('226 Transfer complete'); });
          });
          passive = null;
        } else if (verb === 'QUIT') {reply('221 Bye');socket.end();}
        else reply('502 Not supported');
      }
    });
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  return {port:server.address().port, commands, events, uploaded, cert, key,
    close:()=>new Promise(resolve=>server.close(()=>{fs.rmSync(dir,{recursive:true,force:true});resolve();}))};
}
module.exports={startFtps};
