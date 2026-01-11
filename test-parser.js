import { ParserFactory } from './parser-factory.js';

const source = `
function test() {
  return 42;
}

const arrow = () => {};

export function exported() {}
`;

const tree = await ParserFactory.parse(source, 'javascript');

console.log('Root node:');
console.log(tree.rootNode.toString());

console.log('\n\nChild nodes:');
for (let i = 0; i < tree.rootNode.childCount; i++) {
  const child = tree.rootNode.child(i);
  console.log(`${i}: ${child.type} - ${child.text.substring(0, 80).replace(/\n/g, '\\n')}...`);
}
