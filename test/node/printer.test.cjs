'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const tls = require('node:tls');
const aedes = require('aedes');
const {startFtps} = require('./fake-ftps.cjs');
const {exercise} = require('../../target/printer-test.js');
const printer = {name:'desk', host:'127.0.0.1', serial:'FAKE1',
                 'access-code': {scheme:'env', path:'BAMBU_FAKE_CODE'}};
process.env.BAMBU_FAKE_CODE = 'fake-only';

test('fake MQTT and implicit-FTPS exercise printer ports', {timeout:20000}, async () => {
  const ftp = await startFtps();
  try {
    const mqtt = aedes();
    const server = tls.createServer({key:ftp.key,cert:ftp.cert},mqtt.handle);
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    try {
      mqtt.on('publish',(packet, client)=>{
        if(client && packet.topic==='device/FAKE1/request'){
          mqtt.publish({topic:'device/FAKE1/report',payload:JSON.stringify({mc_print:{gcode_state:'IDLE'}}),qos:0,retain:false},()=>{});
        }
      });
      const result = await exercise(printer,server.address().port,ftp.port);
      assert.deepEqual({...result},{connected:true,duplicate:':printer/owned',published:true,
        state:'IDLE',listing:1,'listing-error':'',download:'fake-printer-gcode\n',
        'download-error':'',closed:true});
      assert.ok(ftp.commands.includes('PROT P'));
    } finally {await new Promise(resolve=>server.close(resolve)); await new Promise(resolve=>mqtt.close(resolve));}
  } finally {await ftp.close();}
});
