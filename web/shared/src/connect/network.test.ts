import { describe, expect, it } from 'vitest';
import { networkOf } from './network';

describe('networkOf', () => {
  it('is an IPv4 address as it is', () => {
    expect(networkOf('203.0.113.7')).toBe('203.0.113.7');
  });

  it('is the /64 of an IPv6 address, so devices on one home network share it', () => {
    expect(networkOf('2001:db8:85a3:1:aaaa:bbbb:cccc:dddd')).toBe('2001:0db8:85a3:0001');
    expect(networkOf('2001:db8:85a3:1:1111:2222:3333:4444')).toBe('2001:0db8:85a3:0001');
  });

  it('tells two networks apart', () => {
    expect(networkOf('2001:db8:85a3:1::5')).not.toBe(networkOf('2001:db8:85a3:2::5'));
  });
});
