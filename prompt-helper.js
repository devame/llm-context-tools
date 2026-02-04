/**
 * Prompt Helper - Interactive CLI prompts
 */

import { createInterface } from 'readline';

/**
 * Prompt user for yes/no confirmation
 * @param {string} question - Question to ask
 * @returns {Promise<boolean>} - true if user confirmed
 */
export function promptYesNo(question) {
  const rl = createInterface({
    input: process.stdin,
    output: process.stdout
  });

  return new Promise((resolve) => {
    rl.question(`${question} (y/n): `, (answer) => {
      rl.close();
      const normalized = answer.trim().toLowerCase();
      resolve(normalized === 'y' || normalized === 'yes');
    });
  });
}

/**
 * Prompt user to press enter to continue
 * @param {string} message - Message to show
 * @returns {Promise<void>}
 */
export function promptContinue(message = 'Press Enter to continue...') {
  const rl = createInterface({
    input: process.stdin,
    output: process.stdout
  });

  return new Promise((resolve) => {
    rl.question(message, () => {
      rl.close();
      resolve();
    });
  });
}

/**
 * Prompt user with multiple choices
 * @param {string} question - Question to ask
 * @param {string[]} choices - Array of choices
 * @returns {Promise<number>} - Index of selected choice
 */
export function promptChoice(question, choices) {
  const rl = createInterface({
    input: process.stdin,
    output: process.stdout
  });

  return new Promise((resolve) => {
    console.log(question);
    choices.forEach((choice, index) => {
      console.log(`  ${index + 1}. ${choice}`);
    });

    rl.question('\nSelect option (1-' + choices.length + '): ', (answer) => {
      rl.close();
      const num = parseInt(answer, 10);
      if (num >= 1 && num <= choices.length) {
        resolve(num - 1);
      } else {
        console.log('Invalid choice, selecting first option.');
        resolve(0);
      }
    });
  });
}
