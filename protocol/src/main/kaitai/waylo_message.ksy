meta:
  id: waylo_message
  title: Waylo <-> DHL gateway datagram (WPP v1)
  file-extension: wpp
  endian: be
  bit-endian: be
  encoding: UTF-8
  license: MIT
doc: |
  One UDP datagram exchanged between the Waylo gateway and the (mock) DHL
  gateway. Every datagram is exactly one message:

    header (19 bytes) | body (variable, switched on header.message_type) | crc32 (4 bytes)

  The first exchange is always Waylo sending `handshake` and DHL answering
  with `handshake_ack`, which carries the session id required by `request`.
  Answers (`handshake_ack`, `response`) reference the message they answer via
  `answer_for_id`; because UDP is stateless this is how the gateway correlates
  answers with in-flight requests.

  Integers are big-endian, bit fields are MSB-first.
doc-ref: ../../../../README.md

seq:
  - id: header
    type: header
  - id: body
    size: header.message_length - header_size - checksum_size
    type:
      switch-on: header.message_type
      cases:
        'message_type::handshake': handshake
        'message_type::handshake_ack': handshake_ack
        'message_type::request': request
        'message_type::response': response
  - id: checksum
    type: u4
    doc: |
      CRC-32 (IEEE 802.3, as java.util.zip.CRC32 / zlib crc32) over every
      preceding byte of the datagram (header + body).

instances:
  header_size:
    value: 19
  checksum_size:
    value: 4

types:
  header:
    seq:
      - id: magic
        contents: WY
      - id: version
        type: u1
        valid: 1
      - id: message_type
        type: u1
        enum: message_type
        valid:
          any-of:
            - message_type::handshake
            - message_type::handshake_ack
            - message_type::request
            - message_type::response
      - id: message_length
        type: u2
        doc: Total datagram length in bytes, including header and checksum.
        valid:
          expr: _ >= 23 and _ == _root._io.size
      - id: message_id
        type: u4
        doc: Sender-assigned id. Retransmissions reuse the id of the original.
      - id: timestamp_ms
        type: u8
        doc: Send time, milliseconds since the Unix epoch (UTC).
      - id: flags
        type: flags

  flags:
    doc: One byte, every bit means something different.
    seq:
      - id: retransmission
        type: b1
        doc: Bit 7 - this datagram is a retry of an earlier one with the same message_id.
      - id: priority
        type: b1
        doc: Bit 6 - the sender asks for expedited processing.
      - id: error
        type: b1
        doc: Bit 5 - set on answers whose status is not a success.
      - id: simulated
        type: b1
        doc: Bit 4 - the payload is simulated (set by the DHL mock).
      - id: reserved
        type: b4
        doc: Bits 3..0 - reserved, must be zero.
        valid: 0

  handshake:
    seq:
      - id: client_name_len
        type: u1
      - id: client_name
        type: str
        size: client_name_len
      - id: nonce
        type: u4
        doc: Random value the server must echo in handshake_ack.

  handshake_ack:
    seq:
      - id: answer_for_id
        type: u4
      - id: status
        type: u1
        enum: handshake_status
      - id: session_id
        type: u4
      - id: nonce_echo
        type: u4
      - id: server_name_len
        type: u1
      - id: server_name
        type: str
        size: server_name_len

  request:
    seq:
      - id: session_id
        type: u4
      - id: operation
        type: u1
        enum: operation
      - id: num_records
        type: u1
      - id: records
        type: record
        repeat: expr
        repeat-expr: num_records

  response:
    seq:
      - id: answer_for_id
        type: u4
      - id: status
        type: u1
        enum: response_status
      - id: num_records
        type: u1
      - id: records
        type: record
        repeat: expr
        repeat-expr: num_records

  record:
    doc: Type-length-value sub-structure; the array element of varying length.
    seq:
      - id: record_type
        type: u1
        enum: record_type
      - id: body_len
        type: u1
      - id: body
        size: body_len
        type:
          switch-on: record_type
          cases:
            'record_type::tracking_number': rec_tracking_number
            'record_type::weight': rec_weight
            'record_type::dimensions': rec_dimensions
            'record_type::destination': rec_destination
            'record_type::tracking_event': rec_tracking_event
            'record_type::price': rec_price

  rec_tracking_number:
    seq:
      - id: value
        type: str
        size-eos: true
        encoding: ASCII

  rec_weight:
    seq:
      - id: grams
        type: u4

  rec_dimensions:
    seq:
      - id: length_mm
        type: u2
      - id: width_mm
        type: u2
      - id: height_mm
        type: u2

  rec_destination:
    seq:
      - id: country_code
        type: str
        size: 2
        encoding: ASCII
        doc: ISO 3166-1 alpha-2
      - id: postal_code
        type: str
        size-eos: true
        encoding: ASCII

  rec_tracking_event:
    seq:
      - id: timestamp_ms
        type: u8
      - id: status
        type: u1
        enum: parcel_status
      - id: location
        type: str
        size-eos: true

  rec_price:
    seq:
      - id: amount_cents
        type: u4
      - id: currency
        type: str
        size: 3
        encoding: ASCII
        doc: ISO 4217

enums:
  message_type:
    0x01: handshake
    0x02: handshake_ack
    0x10: request
    0x11: response

  handshake_status:
    0: accepted
    1: rejected

  operation:
    1: track
    2: quote

  response_status:
    0: ok
    1: invalid_session
    2: bad_request
    3: internal_error

  record_type:
    0x01: tracking_number
    0x02: weight
    0x03: dimensions
    0x04: destination
    0x10: tracking_event
    0x11: price

  parcel_status:
    1: label_created
    2: picked_up
    3: in_transit
    4: out_for_delivery
    5: delivered
    6: exception
