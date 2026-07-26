import os
import sys
import json
import hashlib
import base64
import time
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

print("=================================================================")
print("NeuroVault: Distributed Multi-Host Simulation Test")
print("=================================================================")

# Setup virtual host storage directories
BASE_DIR = os.path.join(os.path.dirname(__file__), "virtual_hosts")
os.makedirs(BASE_DIR, exist_ok=True)

HOST_A_DIR = os.path.join(BASE_DIR, "host_node_a")
HOST_B_DIR = os.path.join(BASE_DIR, "host_node_b")
HOST_C_DIR = os.path.join(BASE_DIR, "host_node_c_replica")

for hdir in [HOST_A_DIR, HOST_B_DIR, HOST_C_DIR]:
    os.makedirs(hdir, exist_ok=True)

print("[1/5] Initialized Virtual Storage Containers for 3 Host Nodes:")
print(f"  - Host A (Primary Node 1): {HOST_A_DIR}")
print(f"  - Host B (Primary Node 2): {HOST_B_DIR}")
print(f"  - Host C (Replica Node):  {HOST_C_DIR}")

# Step 1: Generate Client Payload & AES-256-GCM Key
sample_text = "NeuroVault Distributed Zero-Trust Payload - " + ("A" * 1024 * 1024 * 5) # ~5 MB file
original_bytes = sample_text.encode('utf-8')
original_sha256 = hashlib.sha256(original_bytes).hexdigest()

print(f"\n[2/5] Client Payload Created:")
print(f"  - Original Size: {len(original_bytes)} bytes (~5.0 MB)")
print(f"  - Original SHA-256: {original_sha256[:16]}...")

# Client Encryption
aes_key = AESGCM.generate_key(bit_length=256)
aesgcm = AESGCM(aes_key)

# Split into 4MB Chunks
CHUNK_SIZE = 4 * 1024 * 1024
raw_chunks = [original_bytes[i:i + CHUNK_SIZE] for i in range(0, len(original_bytes), CHUNK_SIZE)]

print(f"\n[3/5] Client-Side Encryption & Chunking (AES-256-GCM):")
print(f"  - Total Chunks: {len(raw_chunks)}")

encrypted_chunks = []
for idx, chunk in enumerate(raw_chunks):
    nonce = os.urandom(12)
    encrypted_payload = aesgcm.encrypt(nonce, chunk, None)
    full_encrypted_blob = nonce + encrypted_payload # Nonce + Ciphertext
    chunk_checksum = hashlib.sha256(full_encrypted_blob).hexdigest()
    
    encrypted_chunks.append({
        'id': f"chunk_{idx}_{int(time.time())}",
        'index': idx,
        'blob': full_encrypted_blob,
        'checksum': chunk_checksum,
    })
    print(f"  - Chunk {idx}: Encrypted size={len(full_encrypted_blob)} bytes | SHA-256={chunk_checksum[:12]}...")

# Step 3: Distribute Chunks across Host Nodes (Direct Host Storage)
print(f"\n[4/5] Distributing Chunks across Host Nodes & Replicas:")
# Chunk 0 -> Host A (Primary), Replica -> Host C
chunk0_file = os.path.join(HOST_A_DIR, f"{encrypted_chunks[0]['id']}.bin")
with open(chunk0_file, "wb") as f:
    f.write(encrypted_chunks[0]['blob'])
print(f"  - Chunk 0 written to Host A: {chunk0_file}")

# Chunk 1 -> Host B (Primary), Replica -> Host C
chunk1_file = os.path.join(HOST_B_DIR, f"{encrypted_chunks[1]['id']}.bin")
with open(chunk1_file, "wb") as f:
    f.write(encrypted_chunks[1]['blob'])
print(f"  - Chunk 1 written to Host B: {chunk1_file}")

# Replica of Chunk 0 -> Host C
replica0_file = os.path.join(HOST_C_DIR, f"{encrypted_chunks[0]['id']}_replica.bin")
with open(replica0_file, "wb") as f:
    f.write(encrypted_chunks[0]['blob'])
print(f"  - Chunk 0 Replica written to Host C: {replica0_file}")

# Step 4: Client Download, Decryption & Reassembly
print(f"\n[5/5] Client Retrieval, Integrity Check & Decryption:")

# Read Chunk 0 from Host A
with open(chunk0_file, "rb") as f:
    read_blob_0 = f.read()

# Read Chunk 1 from Host B
with open(chunk1_file, "rb") as f:
    read_blob_1 = f.read()

read_blobs = [read_blob_0, read_blob_1]
decrypted_chunks = []

for idx, blob in enumerate(read_blobs):
    # Verify Checksum
    read_checksum = hashlib.sha256(blob).hexdigest()
    expected_checksum = encrypted_chunks[idx]['checksum']
    assert read_checksum == expected_checksum, f"Checksum mismatch on Chunk {idx}!"
    
    # Decrypt with AES-256-GCM
    nonce = blob[:12]
    ciphertext = blob[12:]
    decrypted_plain = aesgcm.decrypt(nonce, ciphertext, None)
    decrypted_chunks.append(decrypted_plain)
    print(f"  - Chunk {idx}: Checksum OK ({read_checksum[:12]}...) | Decrypted successfully ({len(decrypted_plain)} bytes)")

# Merge chunks
restored_bytes = b"".join(decrypted_chunks)
restored_sha256 = hashlib.sha256(restored_bytes).hexdigest()

print(f"\n-----------------------------------------------------------------")
print(f"VERIFICATION RESULT:")
print(f"  - Original SHA-256: {original_sha256}")
print(f"  - Restored SHA-256: {restored_sha256}")
assert restored_sha256 == original_sha256, "FINAL INTEGRITY FAILURE!"
print("SUCCESS: Multi-Host Distributed Storage & Decryption PASSED (100% Match!)")
print("=================================================================")
