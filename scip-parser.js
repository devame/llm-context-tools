#!/usr/bin/env node
/**
 * SCIP Index Parser - Examines SCIP output and shows structure
 */

import { readFileSync } from 'fs';
import protobuf from 'protobufjs';

const scipFile = '.llm-context/index.scip';
const protoFile = '.llm-context/scip.proto';

async function parseScip() {
  try {
    // Load the protobuf schema
    const root = await protobuf.load(protoFile);
    const Index = root.lookupType('scip.Index');

    // Read and decode the SCIP file
    const buffer = readFileSync(scipFile);
    const index = Index.decode(buffer);

    const indexObj = Index.toObject(index, {
      longs: String,
      enums: String,
      bytes: String,
    });

    console.log('=== SCIP Index Summary ===\n');

    const metadata = indexObj.metadata || {};
    console.log('Metadata:');
    console.log('  Tool:', metadata.toolInfo?.name || 'N/A', metadata.toolInfo?.version || '');
    console.log('  Project Root:', metadata.projectRoot || 'N/A');

    const documents = indexObj.documents || [];
    console.log('\nDocuments indexed:', documents.length);

    // Group files by directory
    const byDir = {};
    documents.forEach(doc => {
      const dir = doc.relativePath.split('/').slice(0, -1).join('/') || '.';
      byDir[dir] = (byDir[dir] || 0) + 1;
    });

    console.log('\nFiles by directory:');
    Object.entries(byDir).slice(0, 10).forEach(([dir, count]) => {
      console.log(`  ${dir}: ${count} files`);
    });

    // Analyze first few documents in detail
    console.log('\n=== Sample Documents (first 3) ===\n');

    documents.slice(0, 3).forEach((doc, idx) => {
      console.log(`\n[${idx + 1}] ${doc.relativePath}`);
      console.log(`    Language: ${doc.language || 'unknown'}`);

      const occurrences = doc.occurrences || [];
      const symbols = doc.symbols || [];

      console.log(`    Occurrences: ${occurrences.length}`);
      console.log(`    Symbols defined: ${symbols.length}`);

      // Show first few symbols
      if (symbols.length > 0) {
        console.log('\n    Symbol Details:');
        symbols.slice(0, 5).forEach(sym => {
          const symStr = sym.symbol || '';
          const kind = sym.kind || 0;
          const docs = sym.documentation || [];
          const sig = sym.signatureDocumentation?.text || '';

          // Parse symbol to get just the name
          const parts = symStr.split('/');
          const name = parts[parts.length - 1]?.replace(/[.`]/g, '') || symStr;

          const kindNames = {
            1: 'Unknown',
            6: 'Class',
            8: 'Method',
            9: 'Function',
            10: 'Property',
            12: 'Variable',
          };

          console.log(`      - ${name}`);
          console.log(`        Kind: ${kindNames[kind] || kind}`);
          if (sig) {
            console.log(`        Sig: ${sig.substring(0, 60)}${sig.length > 60 ? '...' : ''}`);
          }
          if (docs.length > 0) {
            const docText = docs[0];
            console.log(`        Doc: ${docText.substring(0, 60)}${docText.length > 60 ? '...' : ''}`);
          }
        });
      }

      // Show sample occurrences (function calls/references)
      if (occurrences.length > 0) {
        console.log('\n    Sample Occurrences (first 5):');
        occurrences.slice(0, 5).forEach(occ => {
          const range = occ.range || [];
          const symbol = occ.symbol || '';
          const roles = occ.symbolRoles || 0;

          // Parse symbol to get readable name
          const parts = symbol.split('/');
          const name = parts[parts.length - 1]?.replace(/[.`]/g, '') || symbol;

          const roleNames = {
            1: 'Definition',
            2: 'Import',
            4: 'Reference',
            8: 'WriteAccess',
            16: 'ReadAccess',
          };

          console.log(`      - ${name}`);
          console.log(`        Line: ${range[0] || 0}, Roles: ${roleNames[roles] || roles}`);
        });
      }
    });

    // Global statistics
    console.log('\n\n=== Global Statistics ===\n');

    let totalOccurrences = 0;
    let totalSymbols = 0;
    let symbolsByKind = {};
    let functionSymbols = [];

    documents.forEach(doc => {
      const symbols = doc.symbols || [];
      const occurrences = doc.occurrences || [];

      totalSymbols += symbols.length;
      totalOccurrences += occurrences.length;

      symbols.forEach(sym => {
        const kind = sym.kind || 0;
        symbolsByKind[kind] = (symbolsByKind[kind] || 0) + 1;

        // Collect function symbols for later analysis
        if (kind === 9 || kind === 8) { // Function or Method
          functionSymbols.push({
            name: sym.symbol,
            file: doc.relativePath,
            sig: sym.signatureDocumentation?.text || '',
            doc: (sym.documentation || [])[0] || ''
          });
        }
      });
    });

    console.log('Total Symbols:', totalSymbols);
    console.log('Total Occurrences:', totalOccurrences);
    console.log('\nSymbols by Kind:');

    const kindNames = {
      1: 'Unknown',
      2: 'Namespace',
      3: 'Type',
      6: 'Class',
      8: 'Method',
      9: 'Function',
      10: 'Property',
      12: 'Variable',
      13: 'Constant',
    };

    Object.entries(symbolsByKind)
      .sort((a, b) => b[1] - a[1])
      .forEach(([kind, count]) => {
        console.log(`  ${kindNames[kind] || kind}: ${count}`);
      });

    console.log('\nFunctions/Methods found:', functionSymbols.length);
    console.log('Sample functions:');
    functionSymbols.slice(0, 10).forEach(fn => {
      const name = fn.name.split('/').pop().replace(/[.`]/g, '');
      console.log(`  ${name} (${fn.file})`);
      if (fn.sig) {
        console.log(`    Signature: ${fn.sig.substring(0, 70)}`);
      }
    });

    // Save full data for transformer
    console.log('\n\n=== Saving parsed data for transformer ===');
    const fs = await import('fs');
    fs.writeFileSync('.llm-context/scip-parsed.json', JSON.stringify(indexObj, null, 2));
    console.log('Saved to .llm-context/scip-parsed.json');

  } catch (error) {
    console.error('Error parsing SCIP file:', error.message);
    console.error(error.stack);
  }
}

parseScip();
