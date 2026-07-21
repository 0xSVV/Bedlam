package golib

import (
	"encoding/base64"
	"encoding/binary"
	"encoding/pem"
	"errors"
	"fmt"
	"strings"
)

const (
	pemBlockECHConfigs = "ECH CONFIGS"

	supportedECHVersion = 0xfe0d
)

func parseECHConfigList(s string) ([]byte, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return nil, errors.New("empty ECH config list")
	}
	if list, ok := decodeECHConfigList(s); ok {
		return list, nil
	}
	if blob := findPEMBlock([]byte(s), pemBlockECHConfigs); blob != nil {
		if err := validateECHConfigList(blob); err != nil {
			return nil, err
		}
		return blob, nil
	}
	return nil, errors.New("not a valid base64 ECH config list or ECH CONFIGS PEM block")
}

func decodeECHConfigList(s string) ([]byte, bool) {
	for _, enc := range []*base64.Encoding{
		base64.StdEncoding, base64.RawStdEncoding,
		base64.URLEncoding, base64.RawURLEncoding,
	} {
		if raw, err := enc.DecodeString(s); err == nil {
			if validateECHConfigList(raw) == nil {
				return raw, true
			}
		}
	}
	return nil, false
}

func findPEMBlock(data []byte, blockType string) []byte {
	rest := data
	for {
		var block *pem.Block
		block, rest = pem.Decode(rest)
		if block == nil {
			return nil
		}
		if block.Type == blockType {
			return block.Bytes
		}
	}
}

func validateECHConfigList(list []byte) error {
	body, rest, err := readU16Prefixed(list)
	if err != nil {
		return fmt.Errorf("malformed ECH config list: %w", err)
	}
	if len(rest) != 0 {
		return errors.New("malformed ECH config list: trailing data")
	}
	n := 0
	supported := 0
	for len(body) > 0 {
		if len(body) < 4 {
			return errors.New("malformed ECH config list: truncated config header")
		}
		version := binary.BigEndian.Uint16(body[:2])
		_, next, err := readU16Prefixed(body[2:])
		if err != nil {
			return fmt.Errorf("malformed ECH config list: %w", err)
		}
		if version == supportedECHVersion {
			supported++
		}
		body = next
		n++
	}
	if n == 0 {
		return errors.New("ECH config list contains no configs")
	}
	if supported == 0 {
		return fmt.Errorf("ECH config list has no config with supported version 0x%04x", supportedECHVersion)
	}
	return nil
}

func readU16Prefixed(data []byte) (payload, rest []byte, err error) {
	if len(data) < 2 {
		return nil, nil, errors.New("truncated length prefix")
	}
	n := int(binary.BigEndian.Uint16(data))
	if len(data) < 2+n {
		return nil, nil, errors.New("length prefix exceeds available data")
	}
	return data[2 : 2+n], data[2+n:], nil
}
