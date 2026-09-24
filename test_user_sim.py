import requests, json, hashlib, hmac, struct, re
from Crypto.Cipher import AES

def xor(a, b): return bytes(x ^ y for x, y in zip(a, b))

imsi = '722340390000126'
ki = bytes.fromhex('51A609FE8A3B18CEE53A5EB2F3D6C051')
opc = bytes.fromhex('A7695F045F0488396480353433A90007')

r1 = requests.get(f'http://localhost:18080/?app=ap2014&operation=AcquireTemporaryToken&EAP_ID=0{imsi}@nai.epc.mnc034.mcc722.3gppnetwork.org')
d1 = r1.json()
pkt = bytes.fromhex(d1['eap-relay-packet'])
eap_id = pkt[1]
rand = pkt[12:28]

cipher = AES.new(ki, AES.MODE_ECB)
temp = xor(cipher.encrypt(xor(rand, opc)), opc)
in2 = bytearray(temp)
in2[15] ^= 1
out2 = xor(cipher.encrypt(bytes(in2)), opc)
res = out2[8:16]

k_aut = hashlib.sha256(imsi.encode() + rand + res).digest()[:16]
at_res = struct.pack('!BBH', 3, 3, len(res) * 8) + res
at_mac_dummy = struct.pack('!BBH', 11, 5, 0) + (b'\x00' * 16)
payload_len = 8 + len(at_res) + len(at_mac_dummy)

eap_res_zeroed = struct.pack('!BBHBBH', 2, eap_id, payload_len, 23, 1, 0) + at_res + at_mac_dummy
mac_val = hmac.new(k_aut, eap_res_zeroed, hashlib.sha1).digest()[:16]
at_mac_real = struct.pack('!BBH', 11, 5, 0) + mac_val

eap_res = struct.pack('!BBHBBH', 2, eap_id, payload_len, 23, 1, 0) + at_res + at_mac_real

r2 = requests.post('http://localhost:18080/', json={'eap-relay-packet': eap_res.hex(), 'eap_session': d1['eap_session']})
print('RONDA 2 STATUS:', r2.status_code)

m = re.search(r'name="TemporaryToken"\s+value="([^"]+)"', r2.text)
token = m.group(1) if m else ''
print('EXTRACTED TOKEN:', token)

r3 = requests.post('http://localhost:18080/', json={
    'app': 'ap2014',
    'operation': 'VerifyPhoneNumber',
    'temporary_token': token,
    'msisdn': '541170000005',
    'entitlement_version': '10.0',
    'requestor_id': '00000000-0000-4000-8000-0000000000b1'
})
print('RONDA 3 STATUS:', r3.status_code)
print('RONDA 3 BODY:', r3.text)
