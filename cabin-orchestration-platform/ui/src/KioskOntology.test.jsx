import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

// Guard the conflict-resolution failure where adjacent append-only records
// became one record with duplicate source/definition fields and one empty ID.
const here = path.dirname(fileURLToPath(import.meta.url));
const ontology = readFileSync(path.join(here, '../../../docs/ontology.yaml'), 'utf8').replace(/\r\n/g, '\n');
describe('kiosk ontology records remain separate', () => {
  it.each([
    ['family_hub_kiosk', 'familyHub.kiosk', 'family-hub/kiosk.js'],
    ['cabin_kiosk', 'cabin.kiosk', 'KioskControls.jsx'],
  ])('%s describes exactly its own persisted preference', (id, key, implementation) => {
    const records = ontology.split(/(?=^  - id: )/m).filter(record => record.startsWith(`  - id: ${id}\n`));
    expect(records).toHaveLength(1);
    const record = records[0];
    expect(record).toContain(`source: localStorage["${key}"]`);
    expect(record).toContain(implementation);
    for (const field of ['entity_type', 'layer', 'source', 'definition', 'transformation', 'notes']) {
      expect(record.match(new RegExp(`^    ${field}:`, 'gm'))).toHaveLength(1);
    }
  });
});
